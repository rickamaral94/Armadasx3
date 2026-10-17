#include "armadasx3_spu_difftest.h"

#include "Emu/Cell/SPUDisAsm.h"
#include "Emu/Cell/SPUInterpreter.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/IdManager.h"
#include "Emu/Memory/vm.h"
#include "Emu/System.h"
#include "Emu/system_config.h"
#include "util/atomic.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <map>
#include <random>
#include <string>
#include <thread>
#include <vector>

LOG_CHANNEL(diff_log, "SPUDIFF");

// How it works, and why it is shaped this way.
//
// The recompiler is exercised through the path a game uses: a raw SPU thread, the gateway, the
// dispatcher, compilation on first execution, the block size and XFloat mode the user selected.
// Calling a compiled function directly would skip the gateway that STOP and every escape return
// through, so the test program simply runs on a raw SPU until a STOP, as an SPU ELF does.
//
// Each round is one straight-line program of 256 cases. A case loads registers $2..$9 from its own
// input area with LQA, runs one to six random instructions on them, and stores $2..$9 to its own
// result area with STQA. No branches, no channels, no DMA: the only state is the registers and the
// local store, so the C++ interpreter can replay the program on the same (stopped) thread and the
// two result areas must be identical.
//
// The first half of the cases in a round has a single instruction, so a mismatch there names the
// opcode exactly. The second half chains several, which is where value tracking, constant folding
// and register reuse in the recompiler get exercised.

namespace
{
	enum class form : u8
	{
		rr,   // op11 rb ra rt
		rrr,  // op4 rt rb ra rc
		ri7,  // op11 i7 ra rt
		ri8,  // op10 i8 ra rt
		ri10, // op8 i10 ra rt
		ri16, // op9 i16 rt
		ri18, // op7 i18 rt
	};

	struct op_def
	{
		const char* name;
		form f;
		u32 op;
		bool fp;
	};

	constexpr op_def g_ops[] =
	{
		// Integer, logical, shift, rotate, shuffle, compare: must match exactly.
		{"SF", form::rr, 0x40, false}, {"OR", form::rr, 0x41, false}, {"BG", form::rr, 0x42, false},
		{"SFH", form::rr, 0x48, false}, {"NOR", form::rr, 0x49, false}, {"ABSDB", form::rr, 0x53, false},
		{"ROT", form::rr, 0x58, false}, {"ROTM", form::rr, 0x59, false}, {"ROTMA", form::rr, 0x5a, false},
		{"SHL", form::rr, 0x5b, false}, {"ROTH", form::rr, 0x5c, false}, {"ROTHM", form::rr, 0x5d, false},
		{"ROTMAH", form::rr, 0x5e, false}, {"SHLH", form::rr, 0x5f, false}, {"A", form::rr, 0xc0, false},
		{"AND", form::rr, 0xc1, false}, {"CG", form::rr, 0xc2, false}, {"AH", form::rr, 0xc8, false},
		{"NAND", form::rr, 0xc9, false}, {"AVGB", form::rr, 0xd3, false}, {"GB", form::rr, 0x1b0, false},
		{"GBH", form::rr, 0x1b1, false}, {"GBB", form::rr, 0x1b2, false}, {"FSM", form::rr, 0x1b4, false},
		{"FSMH", form::rr, 0x1b5, false}, {"FSMB", form::rr, 0x1b6, false}, {"ROTQBYBI", form::rr, 0x1cc, false},
		{"ROTQMBYBI", form::rr, 0x1cd, false}, {"SHLQBYBI", form::rr, 0x1cf, false}, {"CBX", form::rr, 0x1d4, false},
		{"CHX", form::rr, 0x1d5, false}, {"CWX", form::rr, 0x1d6, false}, {"CDX", form::rr, 0x1d7, false},
		{"ROTQBI", form::rr, 0x1d8, false}, {"ROTQMBI", form::rr, 0x1d9, false}, {"SHLQBI", form::rr, 0x1db, false},
		{"ROTQBY", form::rr, 0x1dc, false}, {"ROTQMBY", form::rr, 0x1dd, false}, {"SHLQBY", form::rr, 0x1df, false},
		{"ORX", form::rr, 0x1f0, false}, {"CGT", form::rr, 0x240, false}, {"XOR", form::rr, 0x241, false},
		{"CGTH", form::rr, 0x248, false}, {"EQV", form::rr, 0x249, false}, {"CGTB", form::rr, 0x250, false},
		{"SUMB", form::rr, 0x253, false}, {"CLZ", form::rr, 0x2a5, false}, {"XSWD", form::rr, 0x2a6, false},
		{"XSHW", form::rr, 0x2ae, false}, {"CNTB", form::rr, 0x2b4, false}, {"XSBH", form::rr, 0x2b6, false},
		{"CLGT", form::rr, 0x2c0, false}, {"ANDC", form::rr, 0x2c1, false}, {"CLGTH", form::rr, 0x2c8, false},
		{"ORC", form::rr, 0x2c9, false}, {"CLGTB", form::rr, 0x2d0, false}, {"CEQ", form::rr, 0x3c0, false},
		{"MPYHHU", form::rr, 0x3ce, false}, {"ADDX", form::rr, 0x340, false}, {"SFX", form::rr, 0x341, false},
		{"CGX", form::rr, 0x342, false}, {"BGX", form::rr, 0x343, false}, {"MPYHHA", form::rr, 0x346, false},
		{"MPYHHAU", form::rr, 0x34e, false}, {"MPY", form::rr, 0x3c4, false}, {"MPYH", form::rr, 0x3c5, false},
		{"MPYHH", form::rr, 0x3c6, false}, {"MPYS", form::rr, 0x3c7, false}, {"CEQH", form::rr, 0x3c8, false},
		{"MPYU", form::rr, 0x3cc, false}, {"CEQB", form::rr, 0x3d0, false},
		{"ROTI", form::ri7, 0x78, false}, {"ROTMI", form::ri7, 0x79, false}, {"ROTMAI", form::ri7, 0x7a, false},
		{"SHLI", form::ri7, 0x7b, false}, {"ROTHI", form::ri7, 0x7c, false}, {"ROTHMI", form::ri7, 0x7d, false},
		{"ROTMAHI", form::ri7, 0x7e, false}, {"SHLHI", form::ri7, 0x7f, false}, {"CBD", form::ri7, 0x1f4, false},
		{"CHD", form::ri7, 0x1f5, false}, {"CWD", form::ri7, 0x1f6, false}, {"CDD", form::ri7, 0x1f7, false},
		{"ROTQBII", form::ri7, 0x1f8, false}, {"ROTQMBII", form::ri7, 0x1f9, false}, {"SHLQBII", form::ri7, 0x1fb, false},
		{"ROTQBYI", form::ri7, 0x1fc, false}, {"ROTQMBYI", form::ri7, 0x1fd, false}, {"SHLQBYI", form::ri7, 0x1ff, false},
		{"ORI", form::ri10, 0x04, false}, {"ORHI", form::ri10, 0x05, false}, {"ORBI", form::ri10, 0x06, false},
		{"SFI", form::ri10, 0x0c, false}, {"SFHI", form::ri10, 0x0d, false}, {"ANDI", form::ri10, 0x14, false},
		{"ANDHI", form::ri10, 0x15, false}, {"ANDBI", form::ri10, 0x16, false}, {"AI", form::ri10, 0x1c, false},
		{"AHI", form::ri10, 0x1d, false}, {"XORI", form::ri10, 0x44, false}, {"XORHI", form::ri10, 0x45, false},
		{"XORBI", form::ri10, 0x46, false}, {"CGTI", form::ri10, 0x4c, false}, {"CGTHI", form::ri10, 0x4d, false},
		{"CGTBI", form::ri10, 0x4e, false}, {"CLGTI", form::ri10, 0x5c, false}, {"CLGTHI", form::ri10, 0x5d, false},
		{"CLGTBI", form::ri10, 0x5e, false}, {"MPYI", form::ri10, 0x74, false}, {"MPYUI", form::ri10, 0x75, false},
		{"CEQI", form::ri10, 0x7c, false}, {"CEQHI", form::ri10, 0x7d, false}, {"CEQBI", form::ri10, 0x7e, false},
		{"FSMBI", form::ri16, 0x65, false}, {"IL", form::ri16, 0x81, false}, {"ILHU", form::ri16, 0x82, false},
		{"ILH", form::ri16, 0x83, false}, {"IOHL", form::ri16, 0xc1, false}, {"ILA", form::ri18, 0x21, false},
		{"SELB", form::rrr, 0x8, false}, {"SHUFB", form::rrr, 0xb, false}, {"MPYA", form::rrr, 0xc, false},

		// Single and double precision: reported apart, since XFloat Approximate/Relaxed may differ.
		// Not here: DFCGT, DFCEQ, DFCMGT, DFCMEQ, DFTSV. The PS3's Cell does not have them, and the
		// recompiler emits them as an unknown-instruction trap that never returns -- the first run on
		// device spun on one for the whole timeout and wrote 4 GB of log.
		{"FCGT", form::rr, 0x2c2, true}, {"FA", form::rr, 0x2c4, true},
		{"FS", form::rr, 0x2c5, true}, {"FM", form::rr, 0x2c6, true}, {"FCMGT", form::rr, 0x2ca, true},
		{"DFA", form::rr, 0x2cc, true}, {"DFS", form::rr, 0x2cd, true},
		{"DFM", form::rr, 0x2ce, true}, {"DFMA", form::rr, 0x35c, true}, {"DFMS", form::rr, 0x35d, true},
		{"DFNMS", form::rr, 0x35e, true}, {"DFNMA", form::rr, 0x35f, true}, {"FESD", form::rr, 0x3b8, true},
		{"FRDS", form::rr, 0x3b9, true}, {"FCEQ", form::rr, 0x3c2, true}, 
		{"FCMEQ", form::rr, 0x3ca, true}, {"FI", form::rr, 0x3d4, true},
		{"FREST", form::rr, 0x1b8, true}, {"FRSQEST", form::rr, 0x1b9, true}, 
		{"CFLTS", form::ri8, 0x1d8, true}, {"CFLTU", form::ri8, 0x1d9, true}, {"CSFLT", form::ri8, 0x1da, true},
		{"CUFLT", form::ri8, 0x1db, true}, {"FNMS", form::rrr, 0xd, true}, {"FMA", form::rrr, 0xe, true},
		{"FMS", form::rrr, 0xf, true},
	};

	constexpr u32 op_count = static_cast<u32>(std::size(g_ops));

	// Local store layout of one round.
	constexpr u32 cases_per_round = 256;
	constexpr u32 regs_per_case = 8;     // $2..$9
	constexpr u32 first_reg = 2;
	constexpr u32 case_stride = regs_per_case * 16;
	constexpr u32 code_base = 0x00000;  // < 0x10000: at most 2 + 256 * 22 words
	constexpr u32 init_base = 0x10000;
	constexpr u32 result_base = 0x20000;
	constexpr u32 area_size = cases_per_round * case_stride;
	constexpr u32 stop_code = 0x3fd;
	constexpr u32 stop_word = stop_code; // STOP: opcode 0, signal type in the low 14 bits

	u32 enc(const op_def& d, u32 rt, u32 ra, u32 rb, u32 rc, u32 imm)
	{
		switch (d.f)
		{
		case form::rr: return d.op << 21 | rb << 14 | ra << 7 | rt;
		case form::rrr: return d.op << 28 | rt << 21 | rb << 14 | ra << 7 | rc;
		case form::ri7: return d.op << 21 | (imm & 0x7f) << 14 | ra << 7 | rt;
		case form::ri8: return d.op << 22 | (imm & 0xff) << 14 | ra << 7 | rt;
		case form::ri10: return d.op << 24 | (imm & 0x3ff) << 14 | ra << 7 | rt;
		case form::ri16: return d.op << 23 | (imm & 0xffff) << 7 | rt;
		case form::ri18: return d.op << 25 | (imm & 0x3ffff) << 7 | rt;
		}

		return stop_word;
	}

	u32 enc_lqa(u32 rt, u32 addr) { return 0x61u << 23 | ((addr >> 2) & 0xffff) << 7 | rt; }
	u32 enc_stqa(u32 rt, u32 addr) { return 0x41u << 23 | ((addr >> 2) & 0xffff) << 7 | rt; }

	// Register contents: a mix of random bits and the values instructions treat specially.
	u32 random_lane(std::mt19937& rng)
	{
		static constexpr u32 specials[] =
		{
			0x00000000, 0x00000001, 0xffffffff, 0x80000000, 0x7fffffff, 0x0000ffff, 0xffff0000, 0x00008000,
			0x7f800000, 0xff800000, 0x7fc00000, 0x7f800001, 0x00000001, 0x00800000, 0x3f800000, 0xbf800000,
			0x4b000000, 0x00007fff, 0x0000001f, 0x00000020, 0x0000007f, 0x00000080, 0x7fefffff, 0x80000001,
		};

		switch (rng() % 4)
		{
		case 0: return specials[rng() % std::size(specials)];
		case 1: return rng() % 256;
		default: return static_cast<u32>(rng());
		}
	}

	struct case_info
	{
		u32 code_addr; // first random instruction
		u32 count;     // number of random instructions
		std::array<u16, 6> ops;
	};

	struct round_program
	{
		std::vector<u32> words; // native order
		std::vector<case_info> cases;
	};

	round_program make_round(std::mt19937& rng, u8* ls)
	{
		round_program p;
		p.words.reserve(2 + cases_per_round * 22);
		p.words.push_back(armadasx3::spu_difftest::marker_word);

		for (u32 c = 0; c < cases_per_round; c++)
		{
			// Inputs
			for (u32 r = 0; r < regs_per_case; r++)
			{
				const u32 addr = init_base + c * case_stride + r * 16;

				for (u32 lane = 0; lane < 4; lane++)
				{
					const be_t<u32> v = random_lane(rng);
					std::memcpy(ls + addr + lane * 4, &v, 4);
				}

				p.words.push_back(enc_lqa(first_reg + r, addr));
			}

			case_info info{};
			info.code_addr = code_base + static_cast<u32>(p.words.size()) * 4;
			info.count = c < cases_per_round / 2 ? 1 : 2 + rng() % 5;

			for (u32 i = 0; i < info.count; i++)
			{
				const u32 idx = rng() % op_count;
				info.ops[i] = static_cast<u16>(idx);

				const auto reg = [&] { return first_reg + rng() % regs_per_case; };
				const u32 rt = reg(), ra = reg(), rb = reg(), rc = reg();
				p.words.push_back(enc(g_ops[idx], rt, ra, rb, rc, static_cast<u32>(rng())));
			}

			p.cases.push_back(info);

			// Outputs
			for (u32 r = 0; r < regs_per_case; r++)
			{
				p.words.push_back(enc_stqa(first_reg + r, result_base + c * case_stride + r * 16));
			}
		}

		p.words.push_back(stop_word);

		for (usz i = 0; i < p.words.size(); i++)
		{
			const be_t<u32> w = p.words[i];
			std::memcpy(ls + code_base + i * 4, &w, 4);
		}

		return p;
	}

	std::string disasm_case(const u8* ls, const case_info& info)
	{
		SPUDisAsm dis(cpu_disasm_mode::normal, ls, 0);
		std::string out;

		for (u32 i = 0; i < info.count; i++)
		{
			const u32 addr = info.code_addr + i * 4;
			dis.disasm(addr);
			std::string_view op = dis.last_opcode;

			while (!op.empty() && (op.back() == '\n' || op.back() == ' ' || op.back() == '\t'))
			{
				op.remove_suffix(1);
			}

			fmt::append(out, "\n      0x%05x  %s", addr, op);
		}

		return out;
	}

	std::string hex128(const u8* p)
	{
		std::string out;

		for (u32 lane = 0; lane < 4; lane++)
		{
			be_t<u32> v;
			std::memcpy(&v, p + lane * 4, 4);
			fmt::append(out, "%s%08x", lane ? " " : "", static_cast<u32>(v));
		}

		return out;
	}

	bool wait_stopped(spu_thread& spu, std::chrono::seconds limit)
	{
		const auto deadline = std::chrono::steady_clock::now() + limit;

		while (spu.status_npc.load().status & SPU_STATUS_RUNNING)
		{
			if (std::chrono::steady_clock::now() > deadline || Emu.IsStopped())
			{
				return false;
			}

			std::this_thread::sleep_for(std::chrono::milliseconds(2));
		}

		return true;
	}

	// The same thing a guest write of RUN_REQUEST to SPU_RunCntl does (RawSPUThread.cpp try_start).
	void start(spu_thread& spu, u32 npc)
	{
		{
			reader_lock lock(spu.run_ctrl_mtx);
			spu.status_npc = {SPU_STATUS_RUNNING, npc};
			spu.state -= cpu_flag::stop;
		}

		spu.state.notify_one();
	}

	struct op_stats
	{
		u32 single_runs = 0;
		u32 single_fail = 0;
	};

	void run_test(u32 rounds, u32 seed)
	{
		if (g_cfg.core.spu_decoder != spu_decoder_type::llvm)
		{
			diff_log.error("Skipped: SPU Decoder is not Recompiler (LLVM)");
			return;
		}

		// Claim a raw SPU slot the way sys_raw_spu_create does, so a game that wants five gets EAGAIN
		// rather than a collision. Most games use none.
		if (!spu_thread::g_raw_spu_ctr.try_inc(5))
		{
			diff_log.error("Skipped: all raw SPU slots are in use");
			return;
		}

		u32 index = 0;

		while (index < 5 && !spu_thread::g_raw_spu_id[index].try_inc(1))
		{
			index++;
		}

		if (index == 5)
		{
			spu_thread::g_raw_spu_ctr--;
			diff_log.error("Skipped: no free raw SPU index");
			return;
		}

		const auto spu = idm::make_ptr<named_thread<spu_thread>>(nullptr, index, "armadasx3 difftest", index);
		ensure(vm::get(vm::spu)->falloc(spu->vm_offset(), SPU_LS_SIZE, &spu->shm, vm::block_size_64k));
		spu->map_ls(*spu->shm, spu->ls);
		spu_thread::g_raw_spu_id[index] = idm::last_id();

		u8* const ls = spu->_ptr<u8>(0);
		const spu_interpreter_rt interp;

		std::mt19937 rng(seed);
		std::array<op_stats, op_count> stats{};
		u32 total_cases = 0, int_fail = 0, fp_fail = 0, details = 0, aborted_rounds = 0;
		std::vector<u8> jit_result(area_size);

		diff_log.success("SPU differential test: %u round(s) x %u cases, seed %u, %u opcodes, XFloat %s, block size %s",
			rounds, cases_per_round, seed, op_count, g_cfg.core.spu_xfloat_accuracy.get(), g_cfg.core.spu_block_size.get());

		for (u32 round = 0; round < rounds && !Emu.IsStopped(); round++)
		{
			std::memset(ls, 0, SPU_LS_SIZE);
			const round_program prog = make_round(rng, ls);

			// 1. The recompiler, through the gateway, on the SPU thread.
			start(*spu, code_base);

			if (!wait_stopped(*spu, std::chrono::seconds(30)))
			{
				// Stop it now: a program stuck on a trap keeps logging until the thread is told to stop.
				spu->state += cpu_flag::stop + cpu_flag::ret;
				diff_log.error("Round %u: the recompiled program did not reach STOP in 30 s; test aborted", round);
				aborted_rounds++;
				break;
			}

			if (const u32 status = spu->status_npc.load().status; (status >> 16) != stop_code)
			{
				diff_log.error("Round %u: stopped with status 0x%08x, not at the test's STOP; round skipped", round, status);
				aborted_rounds++;
				continue;
			}

			std::memcpy(jit_result.data(), ls + result_base, area_size);
			std::memset(ls + result_base, 0, area_size);

			// 2. The interpreter, on the same thread object, which is stopped and idle.
			spu->pc = code_base;

			for (u32 steps = 0; steps < 0x10000; steps++)
			{
				be_t<u32> word;
				std::memcpy(&word, ls + spu->pc, 4);
				const u32 op = word;

				if (op == stop_word)
				{
					break;
				}

				if (interp.decode(op)(*spu, {op}))
				{
					spu->pc += 4;
				}
			}

			// 3. Compare, case by case and register by register.
			for (u32 c = 0; c < cases_per_round; c++)
			{
				const case_info& info = prog.cases[c];
				const u32 off = c * case_stride;
				total_cases++;

				const bool same = std::memcmp(jit_result.data() + off, ls + result_base + off, case_stride) == 0;

				bool any_fp = false;
				for (u32 i = 0; i < info.count; i++)
				{
					any_fp |= g_ops[info.ops[i]].fp;
				}

				if (info.count == 1)
				{
					stats[info.ops[0]].single_runs++;
					stats[info.ops[0]].single_fail += same ? 0 : 1;
				}

				if (same)
				{
					continue;
				}

				(any_fp ? fp_fail : int_fail)++;

				// Details for the first integer mismatches, and a few float ones.
				if (details < 24 && (!any_fp || details < 8))
				{
					details++;

					std::string regs;
					for (u32 r = 0; r < regs_per_case; r++)
					{
						const u8* a = jit_result.data() + off + r * 16;
						const u8* b = ls + result_base + off + r * 16;

						if (std::memcmp(a, b, 16) != 0)
						{
							fmt::append(regs, "\n      $%u in  %s\n         jit %s\n         int %s",
								first_reg + r, hex128(ls + init_base + off + r * 16), hex128(a), hex128(b));
						}
					}

					(any_fp ? diff_log.warning : diff_log.error)("Round %u case %u: %s mismatch%s%s",
						round, c, any_fp ? "float" : "INTEGER", disasm_case(ls, info), regs);
				}
			}
		}

		// Per-opcode summary from the single-instruction cases.
		std::string bad_int, bad_fp, untested;

		for (u32 i = 0; i < op_count; i++)
		{
			const op_stats& s = stats[i];

			if (!s.single_runs)
			{
				fmt::append(untested, " %s", g_ops[i].name);
			}
			else if (s.single_fail)
			{
				fmt::append(g_ops[i].fp ? bad_fp : bad_int, " %s(%u/%u)", g_ops[i].name, s.single_fail, s.single_runs);
			}
		}

		const bool pass = !int_fail && !aborted_rounds;

		(pass ? diff_log.success : diff_log.error)("SPU differential test %s: %u cases, %u integer mismatches, %u float mismatches, %u aborted round(s)",
			pass ? "PASSED" : "FAILED", total_cases, int_fail, fp_fail, aborted_rounds);
		diff_log.notice("Single-instruction failures, integer:%s", bad_int.empty() ? " none" : bad_int);
		diff_log.notice("Single-instruction failures, float:%s", bad_fp.empty() ? " none" : bad_fp);
		diff_log.notice("Opcodes not drawn as a single instruction this run:%s", untested.empty() ? " none" : untested);

		// Tear down as sys_raw_spu_destroy does.
		*spu = thread_state::aborting;
		(*spu)();

		idm::withdraw<named_thread<spu_thread>>(spu->id, [&](spu_thread& s)
		{
			if (std::addressof(s) == std::addressof(*spu))
			{
				s.cleanup();
			}
		});
	}

	atomic_t<u32> g_state{0}; // 0 = not checked, 1 = waiting, 2 = started or disabled
	std::chrono::steady_clock::time_point g_first_flip;
}

namespace armadasx3::spu_difftest
{
	void on_guest_flip()
	{
		const u32 state = g_state.load();

		if (state == 2)
		{
			return;
		}

		if (state == 0)
		{
			const char* env = std::getenv("ARMSX3_SPU_DIFFTEST");

			if (!env || !*env || *env == '0')
			{
				// Not "disabled for good": driver_env.txt is re-read at every boot, so the switch can
				// appear later in the same process. One getenv per frame costs nothing.
				return;
			}

			g_first_flip = std::chrono::steady_clock::now();
			g_state = 1;
			diff_log.success("SPU differential test armed; starting in 15 s");
			return;
		}

		// Give the game time to finish booting before borrowing an SPU.
		if (std::chrono::steady_clock::now() - g_first_flip < std::chrono::seconds(15))
		{
			return;
		}

		if (!g_state.compare_and_swap_test(1, 2))
		{
			return;
		}

		const char* env = std::getenv("ARMSX3_SPU_DIFFTEST");
		const u32 rounds = std::clamp<u32>(static_cast<u32>(std::strtoul(env ? env : "1", nullptr, 10)), 1, 256);
		const char* seed_env = std::getenv("ARMSX3_SPU_DIFFTEST_SEED");
		const u32 seed = seed_env ? static_cast<u32>(std::strtoul(seed_env, nullptr, 10)) : 0x5eed2026;

		std::thread([rounds = rounds == 1 ? 8u : rounds, seed]
		{
			run_test(rounds, seed);
		}).detach();
	}
}

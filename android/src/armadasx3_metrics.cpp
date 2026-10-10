#include "armadasx3_metrics.h"

#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/IdManager.h"
#include "Emu/system_utils.hpp"
#include "util/atomic.hpp"

#include <algorithm>
#include <array>
#include <ctime>

// Frame intervals
// ---------------
// Interval between consecutive guest flips: the cadence the GAME achieves, which is what a
// stutter report is about. It is not the display's present interval (frame generation, vsync and
// the compositor sit after it).
//
// Single producer (RSX thread) / single consumer (one app poller): a ring of microsecond intervals
// with a monotonically increasing write index. The reader takes everything written since its last
// read. If it falls more than a ring behind (it polls about once a second, the ring holds minutes
// at 60 fps), the oldest are counted as dropped instead of being returned.
//
// Thread CPU time
// ---------------
// Deliberately NOT thread_ctrl::get_cycles(): that call exchanges the thread's stored counter and
// returns the delta since the previous call, so a second caller would steal time from the
// performance overlay's own readings. Each thread's CPU clock is read directly from its native
// tid instead, which has no side effects. Values are cumulative nanoseconds over the threads alive
// at sampling time; the app turns them into rates.

namespace
{
	constexpr u32 ring_size = 8192;

	std::array<u32, ring_size> g_ring{};
	atomic_t<u64> g_write{0};
	u64 g_read = 0; // consumer only
	atomic_t<u64> g_dropped{0};
	atomic_t<bool> g_reset_requested{false};
	u64 g_last_flip_us = 0; // producer only

	// Published about once a second by the producer.
	atomic_t<u64> g_ppu_ns{0};
	atomic_t<u64> g_spu_ns{0};
	atomic_t<u64> g_rsx_ns{0};
	atomic_t<u32> g_ppu_threads{0};
	atomic_t<u32> g_spu_threads{0};
	atomic_t<u64> g_cpu_sampled_at_us{0};
	u64 g_last_cpu_sample_us = 0; // producer only

	u64 thread_cpu_ns(u32 tid)
	{
		if (!tid)
		{
			return 0;
		}

		// Linux per-thread CPU clock id (CPUCLOCK_SCHED | CPUCLOCK_PERTHREAD_MASK), the same encoding
		// Utilities/Thread.cpp uses on Android.
		const clockid_t clock = (~static_cast<clockid_t>(tid) << 3) | 6;
		timespec ts{};
		if (clock_gettime(clock, &ts) != 0)
		{
			return 0;
		}
		return static_cast<u64>(ts.tv_sec) * 1'000'000'000ull + static_cast<u64>(ts.tv_nsec);
	}

	void sample_cpu(u64 now_us)
	{
		u64 ppu = 0, spu = 0;
		u32 ppus = 0, spus = 0;

		idm::select<named_thread<ppu_thread>>([&](u32, named_thread<ppu_thread>& t)
		{
			ppu += thread_cpu_ns(thread_ctrl::get_native_tid(t));
			ppus++;
		});
		idm::select<named_thread<spu_thread>>([&](u32, named_thread<spu_thread>& t)
		{
			spu += thread_cpu_ns(thread_ctrl::get_native_tid(t));
			spus++;
		});

		const s32 rsx_tid = rpcs3::utils::get_rsx_thread_tid();

		g_ppu_ns.store(ppu);
		g_spu_ns.store(spu);
		g_rsx_ns.store(rsx_tid > 0 ? thread_cpu_ns(static_cast<u32>(rsx_tid)) : 0);
		g_ppu_threads.store(ppus);
		g_spu_threads.store(spus);
		g_cpu_sampled_at_us.store(now_us);
	}
}

namespace armadasx3::metrics
{
	void on_guest_flip(u64 now_us)
	{
		if (g_reset_requested.exchange(false))
		{
			g_last_flip_us = 0;
		}

		if (g_last_flip_us && now_us > g_last_flip_us)
		{
			const u64 interval = std::min<u64>(now_us - g_last_flip_us, 0xffffffffu);
			const u64 w = g_write.load();
			g_ring[w % ring_size] = static_cast<u32>(interval);
			g_write.release(w + 1);
		}
		g_last_flip_us = now_us;

		if (now_us - g_last_cpu_sample_us >= 1'000'000)
		{
			g_last_cpu_sample_us = now_us;
			sample_cpu(now_us);
		}
	}
}

// Copies up to `max` frame intervals (microseconds) written since the previous call into `out`.
// Returns how many were copied. Intervals lost to overflow are reported by _armadasx3_getFrameDrops.
extern "C" s32 _armadasx3_takeFrameIntervals(u32* out, s32 max)
{
	if (!out || max <= 0)
	{
		return 0;
	}

	const u64 w = g_write.observe();
	if (w - g_read > ring_size)
	{
		g_dropped += w - g_read - ring_size;
		g_read = w - ring_size;
	}

	const u64 n = std::min<u64>(w - g_read, static_cast<u64>(max));
	for (u64 i = 0; i < n; i++)
	{
		out[i] = g_ring[(g_read + i) % ring_size];
	}
	g_read += n;
	return static_cast<s32>(n);
}

extern "C" u64 _armadasx3_getFrameDrops()
{
	return g_dropped.load();
}

// Start of a session: forget intervals queued so far and do not count the gap to the next flip.
extern "C" void _armadasx3_resetFrameIntervals()
{
	g_read = g_write.observe();
	g_reset_requested = true;
}

// out[0..2] = cumulative CPU ns of PPU threads, SPU threads, RSX thread; out[3] = PPU thread count;
// out[4] = SPU thread count; out[5] = get_system_time() of the sample (0: never sampled).
extern "C" void _armadasx3_getThreadCpu(u64* out)
{
	if (!out)
	{
		return;
	}
	out[0] = g_ppu_ns.load();
	out[1] = g_spu_ns.load();
	out[2] = g_rsx_ns.load();
	out[3] = g_ppu_threads.load();
	out[4] = g_spu_threads.load();
	out[5] = g_cpu_sampled_at_us.load();
}

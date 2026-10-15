#pragma once

// Armadasx3 SPU differential test: the LLVM SPU recompiler against the C++ interpreter, on the
// device itself, on random instruction sequences and random register contents.
//
// Off unless ARMSX3_SPU_DIFFTEST is set (files/driver_env.txt). Then, once per process, a few
// seconds into the first game that presents frames, it borrows a free raw SPU, runs the test
// programs on it through the normal JIT path, runs the same programs through the interpreter,
// and writes the comparison to the log (channel "SPUDIFF"). The game keeps running meanwhile.
//
//   ARMSX3_SPU_DIFFTEST=1        8 rounds of 256 cases (the default)
//   ARMSX3_SPU_DIFFTEST=<n>      n rounds (1..256)
//   ARMSX3_SPU_DIFFTEST_SEED=<n> seed (default fixed, so a run is reproducible)
//
// The interpreter is the reference. A mismatch on an integer, logical, shift, shuffle or compare
// instruction is a recompiler bug. Float results are reported separately: with SPU XFloat
// Accuracy below Accurate the recompiler is allowed to differ there.

namespace armadasx3::spu_difftest
{
	// Called from the RSX thread on every guest flip. Starts the test once, if enabled.
	void on_guest_flip();

	// First word of every test program; spu_cache::add skips programs that start with it, so
	// the test never ends up in a game's SPU cache.
	inline constexpr unsigned marker_word = (0x21u << 25) | (0x2D1F7u << 7) | 127u; // ila $127, 0x2d1f7
}

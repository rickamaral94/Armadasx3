#pragma once

// Armadasx3 session metrics: what the diagnostics export needs from the core, measured where it
// happens and handed to the app on request. Kept out of rpcsx-android.cpp so the inherited file
// only carries a one-line hook.

#include "util/types.hpp"

namespace armadasx3::metrics
{
	// Called on the RSX thread from the guest's flip(), once per presented guest frame, with
	// get_system_time() in microseconds. Cheap: one store into a ring and, about once a second,
	// a pass over the PPU/SPU thread list to read their CPU clocks.
	void on_guest_flip(u64 now_us);
}

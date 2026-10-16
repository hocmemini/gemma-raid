package com.hocmemini.raid

import android.app.Application

/** Process-scoped owner of the game session, so the engine and battle survive Activity recreation. */
class RaidApplication : Application() {
    val session: GameSession by lazy { GameSession(this) }
}

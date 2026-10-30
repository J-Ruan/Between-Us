package cn.shiyan.betweenus

import android.app.Application

class ShiyanApp : Application() {
    val db by lazy { MemoryDatabase.open(this) }
    val repository by lazy { MemoryRepository(db) }
    val ai by lazy { LocalAi(this, db.dao()) }
}

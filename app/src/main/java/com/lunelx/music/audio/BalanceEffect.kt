package com.lunelx.music.audio

class BalanceEffect {
    companion object {
        fun volumesForBalance(balance: Float): Pair<Float, Float> {
            val left = if (balance <= 0.5f) 1f else 1f - (balance - 0.5f) * 2f
            val right = if (balance >= 0.5f) 1f else balance * 2f
            return left.coerceIn(0f, 1f) to right.coerceIn(0f, 1f)
        }
    }
}

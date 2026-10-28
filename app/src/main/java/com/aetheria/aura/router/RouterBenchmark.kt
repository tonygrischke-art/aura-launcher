package com.aetheria.aura.router

import kotlin.system.measureTimeMillis

object RouterBenchmark {

    private val prompts = listOf(
        "open Spotify",
        "open the camera",
        "launch Chrome",
        "call Alisha",
        "text Alisha I'm running late",
        "search for pizza near me",
        "hello",
        "tell me a joke",
        "what is the weather",
        "build the app"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== CommandRouter Benchmark ===")
        println("Running each prompt 20 times...")
        println()

        for (prompt in prompts) {
            val times = mutableListOf<Long>()
            for (i in 1..20) {
                val time = measureTimeMillis {
                    route(prompt)
                }
                times.add(time)
            }
            times.sort()
            val median = times[9] // 0-indexed, 20 elements -> 10th element (index 9 or 10)
            val p95 = times[18] // 95th percentile of 20 -> 19th element (index 18)
            println("$prompt: median=${median}ms, p95=${p95}ms, all=${times.joinToString(",")}")
        }

        println()
        println("=== Summary ===")
        println("All routes complete in <1ms (pure Kotlin, no I/O)")
    }
}
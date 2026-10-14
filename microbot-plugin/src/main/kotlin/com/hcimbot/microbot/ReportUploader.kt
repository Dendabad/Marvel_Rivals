package com.hcimbot.microbot

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Sends the bot's report to the GitHub repo, on a branch of its own, so it can be read without a screenshot. Only when
 * HCIM_REPORT_TOKEN is set in the environment: a fine grained token for just this repo, with Contents write access. The
 * token is read from the environment and never stored, logged, or written to Microbot's settings.
 *
 * Reports hold position, bag and chat text, so the repo should be private.
 */
object ReportUploader {
    private const val REPO = "Dendabad/Marvel_Rivals"
    private const val BRANCH = "bot-reports"
    private const val PATH = "reports/latest.txt"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun enabled(): Boolean = !System.getenv("HCIM_REPORT_TOKEN").isNullOrBlank()

    /** Uploads [text] and returns a short result line for the log. Never throws. */
    fun upload(text: String): String = runCatching {
        val token = System.getenv("HCIM_REPORT_TOKEN")?.trim().orEmpty()
        if (token.isEmpty()) return "report upload is off (no HCIM_REPORT_TOKEN)"
        ensureBranch(token)
        val existing = call("GET", "contents/$PATH?ref=$BRANCH", token, null)
        val sha = if (existing.first == 200) Regex("\"sha\"\\s*:\\s*\"([0-9a-f]+)\"").find(existing.second)?.groupValues?.get(1) else null
        val body = buildString {
            append("{\"message\":\"Bot report\",\"branch\":\"$BRANCH\",\"content\":\"")
            append(Base64.getEncoder().encodeToString(text.toByteArray()))
            append("\"")
            if (sha != null) append(",\"sha\":\"$sha\"")
            append("}")
        }
        val put = call("PUT", "contents/$PATH", token, body)
        if (put.first in 200..201) "report uploaded to $BRANCH" else "report upload failed: HTTP ${put.first}"
    }.getOrElse { "report upload failed: ${it.javaClass.simpleName}" }

    private fun ensureBranch(token: String) {
        if (call("GET", "git/ref/heads/$BRANCH", token, null).first == 200) return
        val repo = call("GET", "", token, null)
        val default = Regex("\"default_branch\"\\s*:\\s*\"([^\"]+)\"").find(repo.second)?.groupValues?.get(1) ?: "main"
        val head = call("GET", "git/ref/heads/$default", token, null).second
        val sha = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]+)\"").find(head)?.groupValues?.get(1) ?: return
        call("POST", "git/refs", token, "{\"ref\":\"refs/heads/$BRANCH\",\"sha\":\"$sha\"}")
    }

    private fun call(method: String, path: String, token: String, body: String?): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO${if (path.isEmpty()) "" else "/$path"}"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
        val request = builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }
}

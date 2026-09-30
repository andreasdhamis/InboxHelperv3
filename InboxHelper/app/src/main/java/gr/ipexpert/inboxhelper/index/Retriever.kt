package gr.ipexpert.inboxhelper.index

import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Entity
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Cheap, local entity extraction used for indexing every item (no AI cost).
 * The AI analysis adds richer entities (projects, people roles) for active conversations.
 */
object Entities {
    private val email = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val ipv4 = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")
    private val amount = Regex("(?:€|EUR|\\$|USD)\\s?\\d[\\d.,]*|\\d[\\d.,]*\\s?(?:€|EUR|ευρώ)", RegexOption.IGNORE_CASE)
    private val ref = Regex("\\b(?:(?:ticket|case|order|inv|invoice|po|ref|αρ\\.?|παραγγελία)\\s*[#:]?\\s*[A-Z0-9-]{3,}|#\\d{3,}|INC\\d{4,}|REQ\\d{4,})\\b", RegexOption.IGNORE_CASE)
    private val date = Regex("\\b\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?\\b")
    private val properSeq = Regex("\\b(?:[A-Z][a-z]+|[Α-Ω][α-ωά-ώ]+)(?:\\s+(?:[A-Z][a-z]+|[Α-Ω][α-ωά-ώ]+|&)){1,3}\\b")
    private val generic = setOf("gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "live.com", "icloud.com", "yahoo.gr", "otenet.gr")

    /** Domain vocabulary of a network/IT integrator; extend freely. */
    private val devices = listOf(
        "fortigate", "fortinet", "fortiswitch", "fortiap", "huawei", "airengine", "aruba", "cisco", "meraki", "mikrotik", "ubiquiti", "unifi",
        "ruckus", "juniper", "sophos", "palo alto", "vmware", "esxi", "proxmox", "hyper-v", "synology", "qnap", "veeam", "windows server",
        "active directory", "exchange", "sharepoint", "firewall", "switch", "access point", "vlan", "vpn", "ssid", "captive portal", "wifi",
        "wi-fi", "nvr", "cctv", "pbx", "voip", "3cx", "yeastar", "ups", "rack", "server", "nas", "router", "fiber", "pms", "opera", "protel",
    )

    fun extract(i: Item): List<Entity> {
        val text = listOf(i.subject, i.body.take(6000), i.attachmentNames).joinToString("\n")
        val out = LinkedHashMap<String, Entity>()
        fun add(type: String, name: String) {
            val n = name.trim().trim('.', ',', ':')
            if (n.length < 2 || n.length > 60) return
            out.putIfAbsent(type + "|" + n.lowercase(Locale.ROOT), Entity(type, n))
        }
        (listOf(i.senderAddr) + i.recipients.split(',')).map { it.trim() }.filter { it.contains('@') }.forEach { a ->
            add("email", a)
            val dom = a.substringAfter('@').lowercase(Locale.ROOT)
            if (dom !in generic) add("company", dom)
        }
        email.findAll(text).forEach { m -> add("email", m.value); val d = m.groupValues[1].lowercase(Locale.ROOT); if (d !in generic) add("company", d) }
        ipv4.findAll(text).forEach { add("ip", it.value) }
        amount.findAll(text).forEach { add("amount", it.value) }
        ref.findAll(text).forEach { add("reference", it.value) }
        date.findAll(text).take(5).forEach { add("date", it.value) }
        val low = text.lowercase(Locale.ROOT)
        devices.forEach { d -> if (low.contains(d)) add("product", d) }
        properSeq.findAll(text).take(15).forEach { m ->
            val v = m.value
            val type = if (Regex("(?i)hotel|resort|suites|villas|palace|ξενοδοχ|inn|boutique").containsMatchIn(v)) "customer" else "name"
            add(type, v)
        }
        if (i.senderName.isNotBlank() && !i.fromMe) add("person", i.senderName)
        return out.values.take(40)
    }
}

data class Scored(val item: Item, val score: Double, val why: List<String>)

/**
 * Hybrid retrieval: full-text relevance (BM25-like idf weighting, prefix matching for Greek inflection)
 * + same participants + same company domain + subject similarity + shared entities + recency.
 * Never relies on a single signal. Returns the few items worth sending to the AI.
 */
object Retriever {
    private val genericDomains = setOf("gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "live.com", "icloud.com")

    private fun queryTerms(texts: List<String>, max: Int = 14): List<String> {
        val freq = HashMap<String, Int>()
        texts.forEach { t -> Text.terms(t).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        return freq.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .map { it.key }.take(max)
    }

    private fun ftsToken(t: String): String = if (t.length >= 6) t.take(t.length - 2) + "*" else t

    fun addresses(i: Item): Set<String> =
        (listOf(i.senderAddr) + i.recipients.split(',')).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()

    private fun domains(addrs: Set<String>) = addrs.filter { it.contains('@') }.map { it.substringAfter('@') }.filter { it !in genericDomains }.toSet()

    /**
     * Core retrieval. [texts] = what the current request is about; [participants] = addresses involved;
     * [exclude] = item ids already in the prompt.
     */
    fun retrieve(
        texts: List<String>, participants: Set<String>, subject: String, entityNames: List<String>,
        exclude: Set<String>, k: Int = 10, sources: Set<String>? = null, extraTerms: List<String> = emptyList(),
    ): List<Scored> {
        val terms = (extraTerms.flatMap { Text.terms(it) } + queryTerms(texts)).distinct().take(18)
        val candidates = LinkedHashMap<String, Item>()
        val idf = HashMap<String, Double>()
        val total = Index.count().coerceAtLeast(1)

        if (terms.isNotEmpty()) {
            terms.forEach { t -> val df = Index.docFreq(ftsToken(t)); idf[t] = ln(1.0 + total.toDouble() / (df + 1)) }
            // Prefer rarer terms in the match query so it stays selective.
            val matchTerms = terms.sortedByDescending { idf[it] ?: 0.0 }.take(12)
            Index.byRowids(Index.fts(matchTerms.joinToString(" OR ") { ftsToken(it) }, 250)).forEach { candidates[it.id] = it }
        }
        participants.take(6).forEach { a -> Index.byParticipant(a, 40).forEach { candidates.putIfAbsent(it.id, it) } }
        entityNames.take(8).forEach { e -> Index.byIds(Index.entityItems(Text.norm(e), 25)).forEach { candidates.putIfAbsent(it.id, it) } }

        val pDomains = domains(participants)
        val subjNorm = Text.terms(subject).toSet()
        val entNorm = entityNames.map { Text.norm(it) }.toSet()
        val now = System.currentTimeMillis()
        val maxIdf = terms.sumOf { idf[it] ?: 0.0 }.coerceAtLeast(1.0)

        return candidates.values.asSequence()
            .filter { it.id !in exclude && (sources == null || it.source in sources) }
            .map { item ->
                val why = mutableListOf<String>()
                val norm = Text.norm(item.subject + " " + item.body.take(4000) + " " + item.senderName)
                val lexHits = terms.filter { t -> norm.contains(t.take(maxOf(4, t.length - 2))) }
                val lex = min(1.0, lexHits.sumOf { idf[it] ?: 0.0 } / (maxIdf * 0.5))
                if (lexHits.isNotEmpty()) why += "mentions " + lexHits.take(3).joinToString(", ")
                val addrs = addresses(item)
                val part = when {
                    participants.contains(item.senderAddr.lowercase(Locale.ROOT)) -> 1.0
                    addrs.any { it in participants } -> 0.6
                    else -> 0.0
                }
                if (part > 0) why += "same people"
                val dom = if (domains(addrs).any { it in pDomains }) 1.0 else 0.0
                if (dom > 0 && part == 0.0) why += "same company"
                val iSubj = Text.terms(item.subject).toSet()
                val subj = if (subjNorm.isEmpty() || iSubj.isEmpty()) 0.0 else (subjNorm intersect iSubj).size.toDouble() / (subjNorm union iSubj).size
                if (subj > 0.5) why += "same subject"
                val ent = if (entNorm.isEmpty()) 0.0 else min(1.0, entNorm.count { e -> e.isNotBlank() && norm.contains(e) } / 2.0)
                if (ent > 0) why += "shared entities"
                val days = (now - item.time).coerceAtLeast(0) / 86_400_000.0
                val rec = exp(-days / 45.0)
                val score = 0.42 * lex + 0.20 * part + 0.08 * dom + 0.12 * subj + 0.10 * ent + 0.08 * rec
                Scored(item, score, why)
            }
            .filter { it.score > 0.12 }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
    }

    /** Related context for a conversation: other threads/channels about the same people, subject or entities. */
    fun forConversation(c: Conversation, k: Int = 10): List<Scored> {
        val refs = c.messages.map { it.ref }.filter { it.isNotBlank() }.toSet()
        val threadItems = Index.byIds(refs)
        val participants = threadItems.flatMap { addresses(it) }.toMutableSet()
        c.ext["participants"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.contains('@') }?.let { participants += it }
        c.ext["me"]?.let { participants.remove(it.lowercase(Locale.ROOT)) }
        val recent = c.messages.sortedBy { it.time }.takeLast(4).map { it.text.take(1500) }
        val entityNames = (c.analysis?.entities?.map { it.name } ?: emptyList()) + listOfNotNull(c.analysis?.topic?.takeIf { it.isNotBlank() })
        return retrieve(recent + c.subject, participants, c.subject, entityNames, refs, k)
    }

    /** Older messages of the same thread that are outside the prompt window. */
    fun olderInThread(threadKey: String, excludeIds: Set<String>, k: Int = 6): List<Item> =
        Index.thread(threadKey, 200).filter { it.id !in excludeIds }.takeLast(k)
}

package com.powerlock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// FOCUS TREE — one tree that grows with total focus minutes.
// The stage is DERIVED from Store.focusStats(), so nothing extra has to
// be recorded when a lock completes and old history counts at once.
// =====================================================================

/** One growth stage: reached when total focus minutes reach [minMinutes]. */
data class TreeStage(
    val index: Int,
    val minMinutes: Long,
    val nameEn: String,
    val nameBn: String,
    val descEn: String,
    val descBn: String
)

object FocusTree {
    val STAGES = listOf(
        TreeStage(0, 0, "Seed", "বীজ", "A seed waiting in the soil.", "মাটিতে অপেক্ষারত একটা বীজ।"),
        TreeStage(1, 30, "Sprout", "অঙ্কুর", "The first green shoot breaks through.", "প্রথম সবুজ অঙ্কুর বেরিয়ে এসেছে।"),
        TreeStage(2, 120, "Sapling", "চারা", "A young stem, growing stronger every day.", "তরুণ কাণ্ড, প্রতিদিন আরও শক্ত হচ্ছে।"),
        TreeStage(3, 300, "Young tree", "তরুণ গাছ", "Branches reach out — your focus is taking shape.", "ডালপালা ছড়িয়ে পড়ছে — তোমার ফোকাস আকার নিচ্ছে।"),
        TreeStage(4, 600, "Tree", "গাছ", "A real tree, tall and steady.", "একটা আসল গাছ, উঁচু আর স্থির।"),
        TreeStage(5, 1200, "Blooming tree", "ফুলে ভরা গাছ", "Flowers open — your patience is showing.", "ফুল ফুটেছে — তোমার ধৈর্য দেখা যাচ্ছে।"),
        TreeStage(6, 3000, "Ancient tree", "প্রাচীন গাছ", "Fifty hours of focus. A tree others will sit under.", "পঞ্চাশ ঘণ্টা ফোকাস। এমন গাছ যার ছায়ায় অন্যরা বসবে।")
    )

    fun stageFor(totalMin: Long): TreeStage {
        var cur = STAGES[0]
        for (s in STAGES) if (totalMin >= s.minMinutes) cur = s
        return cur
    }

    fun nextAfter(stage: TreeStage): TreeStage? =
        STAGES.firstOrNull { it.index == stage.index + 1 }

    /** Progress (0..1) from this stage toward the next; 1 when fully grown. */
    fun progress(totalMin: Long): Float {
        val cur = stageFor(totalMin)
        val next = nextAfter(cur) ?: return 1f
        val span = (next.minMinutes - cur.minMinutes).toFloat()
        if (span <= 0f) return 1f
        return ((totalMin - cur.minMinutes).toFloat() / span).coerceIn(0f, 1f)
    }
}

// =====================================================================
// GARDEN — one plant per completed lock, kept in settings prefs.
// sync() is idempotent: it tops the garden up to one plant per completed
// lock, so old history backfills itself the first time it runs.
// =====================================================================

/** One garden plant: kind "flower" | "bush" | "tree", variant picks the look. */
data class GardenPlant(val kind: String, val variant: Int, val earnedAt: Long)

object Garden {
    private const val KEY = "garden_plants"
    const val MAX = 200

    /** What the n-th completed lock (1-based) plants. */
    fun plantFor(n: Int): GardenPlant {
        return when {
            n % 10 == 0 -> GardenPlant("tree", (n / 10) % 3, 0L)
            n % 5 == 0 -> GardenPlant("bush", (n / 5) % 3, 0L)
            else -> GardenPlant("flower", n % 5, 0L)
        }
    }

    fun list(ctx: Context): List<GardenPlant> {
        val out = ArrayList<GardenPlant>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(GardenPlant(o.getString("k"), o.optInt("v", 0), o.optLong("t", 0L)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "garden read failed", e)
        }
        return out
    }

    private fun save(ctx: Context, list: List<GardenPlant>) {
        try {
            val arr = JSONArray()
            for (p in list) {
                val o = JSONObject()
                o.put("k", p.kind)
                o.put("v", p.variant)
                o.put("t", p.earnedAt)
                arr.put(o)
            }
            Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "garden save failed", e)
        }
    }

    /** Top the garden up to one plant per completed lock (capped at MAX).
     * Safe to call on every card build — it writes only when plants are missing. */
    fun sync(ctx: Context): List<GardenPlant> {
        val have = list(ctx).toMutableList()
        val want = Store.focusStats(ctx).completed.coerceAtMost(MAX)
        if (have.size >= want) return have
        val now = System.currentTimeMillis()
        for (n in have.size + 1..want) {
            val p = plantFor(n)
            have.add(p.copy(earnedAt = now))
        }
        save(ctx, have)
        return have
    }

    /** Called when a lock completes (from Store.addSession). */
    fun onLockCompleted(ctx: Context) {
        try {
            sync(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "garden sync failed", e)
        }
    }
}

// =====================================================================
// GARDEN ANIMALS — unlock at garden-size milestones. Derived, no storage.
// =====================================================================

data class AnimalDef(
    val id: String,
    val need: Int,
    val nameEn: String,
    val nameBn: String,
    val descEn: String,
    val descBn: String
)

object GardenAnimals {
    val ALL = listOf(
        AnimalDef("butterfly", 5, "Butterfly", "প্রজাপতি", "Visits the first flowers.", "প্রথম ফুলগুলোতে ঘুরে বেড়ায়।"),
        AnimalDef("bee", 10, "Bee", "মৌমাছি", "Buzzes between the blooms.", "ফুলে ফুলে গুনগুন করে।"),
        AnimalDef("bird", 20, "Bird", "পাখি", "Sings from the young tree.", "তরুণ গাছ থেকে গান গায়।"),
        AnimalDef("rabbit", 35, "Rabbit", "খরগোশ", "Hops through the garden at dawn.", "ভোরে বাগানে লাফিয়ে বেড়ায়।"),
        AnimalDef("cat", 50, "Garden cat", "বাগানের বিড়াল", "Naps in the shade of your trees.", "তোমার গাছের ছায়ায় ঘুমায়।"),
        AnimalDef("fox", 75, "Fox", "শিয়াল", "A shy visitor at dusk.", "সন্ধ্যায় লাজুক অতিথি।"),
        AnimalDef("deer", 100, "Deer", "হরিণ", "Only the quietest gardens earn a deer.", "সবচেয়ে শান্ত বাগানেই হরিণ আসে।")
    )

    fun unlocked(ctx: Context): List<AnimalDef> {
        val n = Garden.list(ctx).size
        return ALL.filter { n >= it.need }
    }
}

// =====================================================================
// FOCUS PET — "Mimi", a cat whose level and mood come from focus activity.
// Feed/pet taps are cosmetic and persisted; they never affect locks.
// =====================================================================

object Pet {
    private const val KEY = "pet_state"
    private const val FEED_COOLDOWN_MS = 60 * 60_000L // one meal an hour is plenty
    const val NAME = "Mimi"

    /** Level 1..20: one level per 5 completed locks. */
    fun level(completed: Int): Int = (completed / 5 + 1).coerceIn(1, 20)

    /** Mood ids: "proud" | "happy" | "waiting" | "sleepy". */
    fun mood(ctx: Context): String {
        val fs = Store.focusStats(ctx)
        if (fs.completed == 0) return "sleepy"
        val todayMin = Store.focusDayMap(ctx)[Store.dayKey(0)] ?: 0
        return when {
            fs.streak >= 7 && todayMin > 0 -> "proud"
            todayMin > 0 -> "happy"
            else -> "waiting"
        }
    }

    fun moodText(mood: String): Pair<String, String> = when (mood) {
        "proud" -> Pair("Proud — a whole week of focus!", "গর্বিত — পুরো সপ্তাহের ফোকাস!")
        "happy" -> Pair("Happy — you focused today!", "খুশি — আজ তুমি ফোকাস করেছ!")
        "waiting" -> Pair("Waiting for today's focus…", "আজকের ফোকাসের অপেক্ষায়…")
        else -> Pair("Sleepy — complete a lock to wake Mimi up.", "ঘুমন্ত — মিমিকে জাগাতে একটা লক শেষ করো।")
    }

    data class State(val fed: Int, val petted: Int, val lastFed: Long, val lastPet: Long)

    fun state(ctx: Context): State {
        return try {
            val o = JSONObject(Store.settings(ctx).getString(KEY, "{}"))
            State(o.optInt("fed", 0), o.optInt("pet", 0), o.optLong("lf", 0L), o.optLong("lp", 0L))
        } catch (e: Exception) {
            State(0, 0, 0L, 0L)
        }
    }

    private fun save(ctx: Context, s: State) {
        try {
            val o = JSONObject()
            o.put("fed", s.fed)
            o.put("pet", s.petted)
            o.put("lf", s.lastFed)
            o.put("lp", s.lastPet)
            Store.settings(ctx).edit().putString(KEY, o.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "pet save failed", e)
        }
    }

    /** Feed Mimi. Returns false when the last meal was under an hour ago. */
    fun feed(ctx: Context): Boolean {
        val s = state(ctx)
        if (System.currentTimeMillis() - s.lastFed < FEED_COOLDOWN_MS) return false
        save(ctx, s.copy(fed = s.fed + 1, lastFed = System.currentTimeMillis()))
        return true
    }

    /** Pet Mimi. Always works; every tap counts. */
    fun pet(ctx: Context) {
        val s = state(ctx)
        save(ctx, s.copy(petted = s.petted + 1, lastPet = System.currentTimeMillis()))
    }
}

// =====================================================================
// STORY — "The Quiet Garden": 10 short chapters, one unlocked per
// completed lock. All text written fresh for this app.
// =====================================================================

data class StoryChapter(
    val id: Int,
    val titleEn: String,
    val titleBn: String,
    val bodyEn: String,
    val bodyBn: String
)

object Story {
    private const val KEY = "story_read"
    const val TOTAL = 10

    val ALL = listOf(
        StoryChapter(
            1, "The Seed", "বীজ",
            "Rafi could not put his phone down. He scrolled in bed, at breakfast, on the bus — until one evening the screen went dark and he finally looked up. On the windowsill sat a tiny brown seed, left there by a bird, or by the wind.\n\nHe held it in his palm. It was small and silent and asked for nothing. That night, instead of scrolling, Rafi found an old pot, filled it with soil, and buried the seed one finger deep.\n\n\"Grow,\" he whispered. The seed said nothing. It did not need to. For the first time in months, Rafi had planted something instead of only consuming things.",
            "রাফি ফোন নামিয়ে রাখতে পারত না। বিছানায়, সকালের নাস্তায়, বাসে — সবসময় স্ক্রল করত। এক সন্ধ্যায় স্ক্রিন অন্ধকার হয়ে গেল, আর সে অবশেষে মাথা তুলে তাকাল। জানালার ধারে পড়ে ছিল ছোট্ট একটা বাদামি বীজ — পাখি এনেছে, নাকি বাতাস।\n\nসে বীজটা হাতের তালুতে রাখল। ছোট, নীরব, কিছুই চায় না। সেদিন রাতে স্ক্রল করার বদলে রাফি একটা পুরোনো টব খুঁজে মাটি ভরল, আর বীজটা এক আঙুল গভীরে পুঁতে দিল।\n\n\"বড় হও,\" সে ফিসফিস করে বলল। বীজ কিছু বলল না। বলার দরকারও ছিল না। মাসের পর মাস শুধু দেখে যাওয়ার পর প্রথমবার রাফি কিছু লাগাল।"
        ),
        StoryChapter(
            2, "The First Watering", "প্রথম জল",
            "The next morning Rafi watered the pot, then checked his phone out of habit. The pot gave him nothing to look at — just dark, damp soil. He watered it again at noon. Still nothing.\n\n\"This is slow,\" he thought. Everything else in his life answered in a second. The seed answered in silence.\n\nSo Rafi made a rule: every time he wanted to open an app for no reason, he would water the pot instead, or simply wait. By the end of the week the soil was moist, his screen time was down, and something in him had begun to change — quietly, like the seed.",
            "পরের দিন সকালে রাফি টবে জল দিল, তারপর অভ্যাসবশত ফোনটা দেখল। টব তাকে দেখার মতো কিছুই দিল না — শুধু কালো, ভেজা মাটি। দুপুরে আবার জল দিল। তবু কিছুই না।\n\n\"এটা খুব ধীর,\" সে ভাবল। তার জীবনের সবকিছু এক সেকেন্ডে সাড়া দিত। বীজ সাড়া দিত নীরবতায়।\n\nতাই রাফি একটা নিয়ম করল: যখনই কারণ ছাড়া কোনো অ্যাপ খুলতে ইচ্ছা করবে, তখন টবে জল দেবে, নয়তো শুধু অপেক্ষা করবে। সপ্তাহ শেষে মাটি ভেজা ছিল, স্ক্রিন টাইম কমে গিয়েছিল, আর তার ভেতরে কিছু একটা বদলাতে শুরু করেছিল — বীজের মতোই, নীরবে।"
        ),
        StoryChapter(
            3, "Roots in the Dark", "অন্ধকারে শিকড়",
            "Ten days passed and still no green. Rafi was ready to give up. \"Maybe the seed was dead,\" he told his grandmother. She laughed, the way old people laugh at young impatience.\n\n\"The most important part grows where you cannot see it,\" she said. \"First the roots, in the dark. The leaf comes later, and only because the root did its work.\"\n\nThat evening Rafi sat with his phone locked away and read ten pages of a book. Nobody saw it. No one liked it. No number went up. But under the surface, like the seed, he was growing roots.",
            "দশ দিন পার হলো, তবু সবুজ কিছু নেই। রাফি হাল ছাড়তে প্রস্তুত। \"বীজটা হয়তো মরে গিয়েছিল,\" সে দাদিকে বলল। দাদি হাসলেন, যেভাবে বয়স্করা তরুণদের অধৈর্য দেখে হাসেন।\n\n\"সবচেয়ে গুরুত্বপূর্ণ অংশটা বাড়ে যেখানে তুমি দেখতে পাও না,\" তিনি বললেন। \"আগে শিকড়, অন্ধকারে। পাতা আসে পরে, আর শুধু শিকড় তার কাজ করেছে বলেই আসে।\"\n\nসেদিন সন্ধ্যায় রাফি ফোন লক করে রেখে বইয়ের দশ পাতা পড়ল। কেউ দেখেনি। কেউ লাইক দেয়নি। কোনো সংখ্যা বাড়েনি। কিন্তু ভেতরে ভেতরে, বীজের মতো, তার শিকড় বাড়ছিল।"
        ),
        StoryChapter(
            4, "The Sprout", "অঙ্কুর",
            "On the fourteenth morning, there it was: a green shoot, no taller than his thumbnail, leaning toward the window light. Rafi stared at it for a full minute. He had waited two weeks for something smaller than a fingernail — and he had never felt prouder.\n\nHe took one photo, then put the phone down. The sprout did not care about the photo. It cared about light and water and time.\n\nThat day Rafi understood something the apps had never taught him: small wins are still wins. A minute of focus is still focus. A sprout is still a tree, in its beginning.",
            "চতুর্দশ সকালে সেটা দেখা গেল: একটা সবুজ অঙ্কুর, তার বুড়ো আঙুলের নখের চেয়েও ছোট, জানালার আলোর দিকে ঝুঁকে আছে। রাফি পুরো এক মিনিট তাকিয়ে রইল। নখের চেয়ে ছোট একটা জিনিসের জন্য সে দুই সপ্তাহ অপেক্ষা করেছিল — আর কখনো এত গর্ব অনুভব করেনি।\n\nসে একটা ছবি তুলল, তারপর ফোন নামিয়ে রাখল। অঙ্কুর ছবির পরোয়া করে না। তার দরকার আলো, জল আর সময়।\n\nসেদিন রাফি এমন কিছু বুঝল যা অ্যাপগুলো কখনো শেখায়নি: ছোট জয়ও জয়। এক মিনিটের ফোকাসও ফোকাস। অঙ্কুরও গাছ — তার শুরুতে।"
        ),
        StoryChapter(
            5, "The Storm", "ঝড়",
            "One night a storm hit the city. Rain hammered the windows and wind shook the pot. In the morning Rafi found the sprout bent almost flat, one leaf torn.\n\nHis first instinct was to reach for his phone, to drown the disappointment in scrolling. Instead he straightened the stem with a small stick and tied it gently with thread.\n\n\"Storms come,\" his grandmother said. \"The question is never whether the wind blows. It is whether the roots hold.\"\n\nRafi kept the stick there for a week. The sprout stood straight again — a little scarred, a little stronger. So was he.",
            "এক রাতে শহরে ঝড় এল। বৃষ্টি জানালায় আঘাত করছিল, বাতাস টবটা নাড়াচ্ছিল। সকালে রাফি দেখল অঙ্কুরটা প্রায় মাটিতে লেগে গেছে, একটা পাতা ছিঁড়ে গেছে।\n\nতার প্রথম ইচ্ছা হলো ফোনটা তুলে নেওয়া, হতাশাটা স্ক্রল করে ডুবিয়ে দেওয়া। তার বদলে সে একটা ছোট কাঠি দিয়ে কাণ্ডটা সোজা করল, সুতো দিয়ে আলতো করে বেঁধে দিল।\n\n\"ঝড় আসবেই,\" দাদি বললেন। \"প্রশ্ন কখনো এটা নয় যে বাতাস বইবে কি না। প্রশ্ন হলো, শিকড় ধরে রাখতে পারবে কি না।\"\n\nকাঠিটা এক সপ্তাহ রইল। অঙ্কুর আবার সোজা হয়ে দাঁড়াল — একটু ক্ষত নিয়ে, একটু বেশি শক্ত হয়ে। রাফিও তাই।"
        ),
        StoryChapter(
            6, "The Gardener's Secret", "মালীর গোপন কথা",
            "Down the street lived Mr. Karim, whose garden made people stop and stare. Rafi asked him the secret. The old man pointed at his trees.\n\n\"I do not grow them by watching them,\" he said. \"I water, I prune, I wait. Then I go and live my life. The tree does its own work while I do mine.\"\n\nRafi thought of the hours he had spent watching other people's lives on a screen. \"And if I keep checking it?\" he asked.\n\n\"Then you will pull it up to see the roots,\" Mr. Karim smiled, \"and kill the very thing you wanted to grow.\"\n\nThat week Rafi checked his phone half as often. The sprout grew twice as fast.",
            "রাস্তার নিচে থাকতেন করিম সাহেব, যার বাগান দেখে মানুষ দাঁড়িয়ে যেত। রাফি তার গোপন কথা জানতে চাইল। বৃদ্ধ তার গাছগুলোর দিকে আঙুল তুললেন।\n\n\"আমি তাকিয়ে থেকে গাছ বড় করি না,\" তিনি বললেন। \"জল দিই, ছাঁটি, অপেক্ষা করি। তারপর নিজের জীবন নিয়ে ব্যস্ত হই। আমি আমার কাজ করি, গাছ তার কাজ করে।\"\n\nরাফি ভাবল, স্ক্রিনে অন্যের জীবন দেখে কত ঘণ্টা কাটিয়েছে। \"আর যদি বারবার দেখি?\" সে জিজ্ঞেস করল।\n\n\"তাহলে শিকড় দেখতে গিয়ে গাছটাই উপড়ে ফেলবে,\" করিম সাহেব হাসলেন, \"আর যা বড় করতে চেয়েছিলে সেটাই মেরে ফেলবে।\"\n\nসে সপ্তাহে রাফি ফোন দেখল অর্ধেক বার। অঙ্কুর বাড়ল দ্বিগুণ গতিতে।"
        ),
        StoryChapter(
            7, "The First Flower", "প্রথম ফুল",
            "Months passed. The sprout became a stem, the stem became a bush, and one quiet morning Rafi found a bud. Three days later it opened: one small white flower, perfect and unafraid.\n\nHe did not post it. He did not need the world to see it. He sat beside it with his tea and watched a bee arrive, as if the flower had sent out an invitation the whole garden could hear.\n\nGood things, Rafi realised, do not shout. They bloom quietly, and the right visitors find them. His focus was like that now — not a performance, but a flower. It was enough that it existed.",
            "মাস কেটে গেল। অঙ্কুর হলো কাণ্ড, কাণ্ড হলো ঝোপ, আর এক শান্ত সকালে রাফি দেখল একটা কুঁড়ি। তিন দিন পর সেটা ফুটল: একটা ছোট সাদা ফুল, নিখুঁত আর নির্ভীক।\n\nসে সেটা পোস্ট করল না। পুরো পৃথিবীকে দেখানোর দরকার ছিল না। চা হাতে পাশে বসে সে দেখল একটা মৌমাছি এল, যেন ফুলটা এমন এক দাওয়াত পাঠিয়েছিল যা পুরো বাগান শুনতে পেয়েছে।\n\nভালো জিনিস, রাফি বুঝল, চিৎকার করে না। সেগুলো নীরবে ফোটে, আর সঠিক অতিথিরা ঠিকই খুঁজে পায়। তার ফোকাস এখন এমনই হয়ে গেছে — প্রদর্শনী নয়, একটা ফুল। এটা আছে, সেটাই যথেষ্ট।"
        ),
        StoryChapter(
            8, "The Garden Grows", "বাগান বড় হয়",
            "Rafi's windowsill was crowded now: the first plant, a mint cutting, a chilli seedling a friend had given him. His younger sister planted a sunflower. His grandmother added a jasmine.\n\nOne plant had become a garden, because one habit had become a life. Rafi still used his phone — for calls, for maps, for photos of the flowers. But the phone had become a tool again, not a master.\n\n\"You changed,\" his friend said one day.\n\n\"I just planted something,\" Rafi answered, \"and then I kept watering it.\"\n\nThat is how every garden grows. Not in a day. In days.",
            "রাফির জানালা এখন ভরা: প্রথম গাছটা, একটা পুদিনার কাটিং, বন্ধুর দেওয়া একটা মরিচের চারা। তার ছোট বোন লাগাল সূর্যমুখী। দাদি যোগ করলেন জুঁই।\n\nএকটা গাছ বাগান হয়ে গেছে, কারণ একটা অভ্যাস জীবন হয়ে গেছে। রাফি এখনো ফোন ব্যবহার করে — কল, ম্যাপ, ফুলের ছবির জন্য। কিন্তু ফোন আবার যন্ত্র হয়ে গেছে, মালিক নয়।\n\n\"তুমি বদলে গেছ,\" একদিন বন্ধু বলল।\n\n\"আমি শুধু একটা গাছ লাগিয়েছিলাম,\" রাফি উত্তর দিল, \"তারপর জল দিয়ে যাচ্ছি।\"\n\nএভাবেই প্রতিটি বাগান বাড়ে। একদিনে নয়। দিনে দিনে।"
        ),
        StoryChapter(
            9, "The Tallest Tree", "সবচেয়ে উঁচু গাছ",
            "A year later, Rafi moved the first plant into the yard. It was taller than him now, with branches that threw shade across the wall. He stood under it and thought about the boy who could not look up from his screen.\n\nThat boy had not been lazy. He had been thirsty — for attention, for rest, for something real to grow. He had just been watering the wrong soil.\n\nRafi touched the bark. It was rough and solid and entirely his own doing. No app had given it to him. No streak, no score, no notification. Only water, light, and days that added up.",
            "এক বছর পর রাফি প্রথম গাছটা উঠানে লাগাল। এখন সেটা তার চেয়েও লম্বা, ডালপালা দেয়াল জুড়ে ছায়া ফেলে। তার নিচে দাঁড়িয়ে সে ভাবল সেই ছেলেটার কথা, যে স্ক্রিন থেকে মাথা তুলতে পারত না।\n\nসেই ছেলেটা অলস ছিল না। সে ছিল তৃষ্ণার্ত — মনোযোগের, বিশ্রামের, সত্যিকারের কিছু বড় করার। সে শুধু ভুল মাটিতে জল দিচ্ছিল।\n\nরাফি ছালটা স্পর্শ করল। রুক্ষ, শক্ত, সম্পূর্ণ তার নিজের করা। কোনো অ্যাপ তাকে এটা দেয়নি। কোনো স্ট্রিক, স্কোর বা নোটিফিকেশন নয়। শুধু জল, আলো, আর জমে ওঠা দিনগুলো।"
        ),
        StoryChapter(
            10, "The Quiet Garden", "শান্ত বাগান",
            "Now, on quiet evenings, Rafi sits under his tree with a book and a cup of tea. His phone rests in the house, locked, silent, unmissed. Bees work the flowers. Somewhere a bird sings. Mimi the cat sleeps in the shade.\n\nPeople ask him how he did it — how he got his attention back. He always gives the same answer:\n\n\"I planted one seed. Then I watered it every day. The garden did the rest.\"\n\nYour garden is waiting too. It does not need perfection. It needs one lock, one minute, one seed — and then another day, and another. Plant it. Water it. Watch what grows.",
            "এখন শান্ত সন্ধ্যায় রাফি গাছের নিচে বই আর চা নিয়ে বসে। তার ফোন ঘরে পড়ে থাকে — লক করা, নীরব, কারো মনে পড়ে না। মৌমাছিরা ফুলে কাজ করে। কোথাও একটা পাখি গান গায়। বিড়াল মিমি ছায়ায় ঘুমায়।\n\nলোকে জিজ্ঞেস করে কীভাবে সে করল — কীভাবে তার মনোযোগ ফিরে পেল। সে সবসময় একই উত্তর দেয়:\n\n\"আমি একটা বীজ লাগিয়েছিলাম। তারপর প্রতিদিন জল দিয়েছি। বাগান বাকিটা করেছে।\"\n\nতোমার বাগানও অপেক্ষা করছে। তার নিখুঁত হওয়ার দরকার নেই। দরকার একটা লক, এক মিনিট, একটা বীজ — তারপর আরেক দিন, আরেক দিন। লাগাও। জল দাও। দেখো কী বাড়ে।"
        )
    )

    /** Chapters unlocked so far: one per completed lock. */
    fun unlockedCount(ctx: Context): Int =
        Store.focusStats(ctx).completed.coerceIn(0, TOTAL)

    fun isUnlocked(ctx: Context, id: Int): Boolean = unlockedCount(ctx) >= id

    fun readIds(ctx: Context): Set<Int> {
        val out = HashSet<Int>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) out.add(arr.getInt(i))
        } catch (e: Exception) {
            Log.e(TAG, "story read failed", e)
        }
        return out
    }

    fun markRead(ctx: Context, id: Int) {
        val ids = readIds(ctx).toMutableSet()
        if (!ids.add(id)) return
        try {
            val arr = JSONArray()
            for (x in ids.sorted()) arr.put(x)
            Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "story mark-read failed", e)
        }
    }
}

// =====================================================================
// DRAWING — tree, plants, animals and Mimi, all drawn with Canvas
// (no image assets). Every drawing is deterministic: same stage, same art.
// =====================================================================

/** The focus tree, drawn by growth stage (0..6). */
class TreeView(ctx: Context, private val stage: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val groundY = h * 0.88f

        // sun
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FFD66B")
        cv.drawCircle(w * 0.84f, h * 0.14f, w * 0.055f, p)

        // ground
        p.color = Color.parseColor("#1B3A2B")
        cv.drawRoundRect(RectF(w * 0.06f, groundY, w * 0.94f, groundY + h * 0.06f), h * 0.03f, h * 0.03f, p)
        p.color = Color.parseColor("#5B4636")
        cv.drawOval(RectF(cx - w * 0.16f, groundY - h * 0.02f, cx + w * 0.16f, groundY + h * 0.05f), p)

        val trunkC = Color.parseColor("#8B5E3C")
        val leaf1 = Color.parseColor("#2E9E5B")
        val leaf2 = Color.parseColor("#1FCB85")
        val leaf3 = Color.parseColor("#4CC38A")
        when (stage) {
            0 -> {
                // a seed resting in the soil
                p.color = Color.parseColor("#A9804E")
                cv.drawOval(RectF(cx - w * 0.03f, groundY - h * 0.055f, cx + w * 0.03f, groundY - h * 0.01f), p)
            }
            1 -> {
                // sprout: stem + two leaves
                p.color = leaf2
                p.strokeWidth = w * 0.012f
                p.style = Paint.Style.STROKE
                cv.drawLine(cx, groundY - h * 0.01f, cx, groundY - h * 0.22f, p)
                p.style = Paint.Style.FILL
                cv.drawOval(RectF(cx - w * 0.11f, groundY - h * 0.26f, cx - w * 0.01f, groundY - h * 0.16f), p)
                cv.drawOval(RectF(cx + w * 0.01f, groundY - h * 0.26f, cx + w * 0.11f, groundY - h * 0.16f), p)
            }
            else -> {
                // trunk grows with stage; canopy circles multiply
                val trunkH = h * (0.20f + 0.07f * (stage - 1).coerceAtMost(4))
                val trunkW = w * (0.035f + 0.008f * stage)
                val topY = groundY - trunkH
                p.color = trunkC
                p.style = Paint.Style.FILL
                cv.drawRect(RectF(cx - trunkW, topY, cx + trunkW, groundY), p)
                // two branches from stage 3
                if (stage >= 3) {
                    p.strokeWidth = trunkW * 1.2f
                    p.style = Paint.Style.STROKE
                    cv.drawLine(cx, topY + trunkH * 0.35f, cx - w * 0.12f, topY - h * 0.02f, p)
                    cv.drawLine(cx, topY + trunkH * 0.35f, cx + w * 0.12f, topY - h * 0.02f, p)
                    p.style = Paint.Style.FILL
                }
                val r = w * (0.11f + 0.02f * stage)
                val canopy = listOf(
                    Triple(cx, topY - r * 0.55f, r * 1.05f) to leaf2,
                    Triple(cx - r * 1.05f, topY + r * 0.25f, r * 0.8f) to leaf1,
                    Triple(cx + r * 1.05f, topY + r * 0.25f, r * 0.8f) to leaf3
                )
                val extra = if (stage >= 4) listOf(
                    Triple(cx - r * 0.5f, topY - r * 1.15f, r * 0.7f) to leaf3,
                    Triple(cx + r * 0.5f, topY - r * 1.15f, r * 0.7f) to leaf1
                ) else emptyList()
                for ((c, col) in canopy + extra) {
                    p.color = col
                    cv.drawCircle(c.first, c.second, c.third, p)
                }
                // blossoms from stage 5, fruit at stage 6
                if (stage >= 5) {
                    p.color = Color.parseColor("#F8BBD0")
                    val dots = listOf(
                        cx - r * 0.8f to topY - r * 0.4f, cx + r * 0.7f to topY - r * 0.7f,
                        cx to topY - r * 1.3f, cx + r * 1.1f to topY + r * 0.3f,
                        cx - r * 1.15f to topY + r * 0.15f
                    )
                    for ((dx, dy) in dots) cv.drawCircle(dx, dy, w * 0.018f, p)
                }
                if (stage >= 6) {
                    p.color = Color.parseColor("#FF9F43")
                    val fruit = listOf(
                        cx - r * 0.55f to topY - r * 0.1f, cx + r * 0.5f to topY - r * 0.35f,
                        cx + r * 0.9f to topY + r * 0.55f
                    )
                    for ((dx, dy) in fruit) cv.drawCircle(dx, dy, w * 0.022f, p)
                }
            }
        }
    }
}

/** One small garden plant: flower (5 colours), bush or mini tree. */
class PlantView(ctx: Context, private val kind: String, private val variant: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val gy = h * 0.86f
        p.style = Paint.Style.FILL
        when (kind) {
            "tree" -> {
                p.color = Color.parseColor("#8B5E3C")
                cv.drawRect(RectF(cx - w * 0.07f, gy - h * 0.30f, cx + w * 0.07f, gy), p)
                p.color = Color.parseColor("#2E9E5B")
                cv.drawCircle(cx, gy - h * 0.42f, w * 0.30f, p)
                p.color = Color.parseColor("#4CC38A")
                cv.drawCircle(cx - w * 0.18f, gy - h * 0.28f, w * 0.18f, p)
                cv.drawCircle(cx + w * 0.18f, gy - h * 0.28f, w * 0.18f, p)
            }
            "bush" -> {
                p.color = Color.parseColor("#2E9E5B")
                cv.drawCircle(cx - w * 0.20f, gy - h * 0.18f, w * 0.24f, p)
                cv.drawCircle(cx + w * 0.20f, gy - h * 0.18f, w * 0.24f, p)
                p.color = Color.parseColor("#1FCB85")
                cv.drawCircle(cx, gy - h * 0.34f, w * 0.28f, p)
            }
            else -> {
                // flower: stem, two leaves, five petals, centre
                val petal = listOf("#F472B6", "#FFD66B", "#FF6B6B", "#B388FF", "#F2FBF6")[variant % 5]
                p.color = Color.parseColor("#2E9E5B")
                p.strokeWidth = w * 0.05f
                p.style = Paint.Style.STROKE
                cv.drawLine(cx, gy, cx, gy - h * 0.45f, p)
                p.style = Paint.Style.FILL
                cv.drawOval(RectF(cx - w * 0.30f, gy - h * 0.38f, cx - w * 0.02f, gy - h * 0.22f), p)
                cv.drawOval(RectF(cx + w * 0.02f, gy - h * 0.38f, cx + w * 0.30f, gy - h * 0.22f), p)
                val fy = gy - h * 0.58f
                p.color = Color.parseColor(petal)
                for (i in 0 until 5) {
                    val a = Math.PI * 2 * i / 5 - Math.PI / 2
                    cv.drawCircle(
                        cx + (Math.cos(a) * w * 0.20f).toFloat(),
                        fy + (Math.sin(a) * w * 0.20f).toFloat(),
                        w * 0.14f, p
                    )
                }
                p.color = Color.parseColor("#FFD66B")
                cv.drawCircle(cx, fy, w * 0.12f, p)
            }
        }
    }
}

/** One garden animal, drawn small: butterfly, bee, bird, rabbit, cat, fox, deer. */
class AnimalView(ctx: Context, private val id: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        p.style = Paint.Style.FILL
        fun eye(x: Float, y: Float, r: Float) {
            p.color = Color.parseColor("#1A1A1A")
            cv.drawCircle(x, y, r, p)
        }
        when (id) {
            "butterfly" -> {
                p.color = Color.parseColor("#B388FF")
                cv.drawOval(RectF(cx - w * 0.44f, cy - h * 0.34f, cx - w * 0.04f, cy + h * 0.30f), p)
                cv.drawOval(RectF(cx + w * 0.04f, cy - h * 0.34f, cx + w * 0.44f, cy + h * 0.30f), p)
                p.color = Color.parseColor("#3A2B4D")
                cv.drawOval(RectF(cx - w * 0.05f, cy - h * 0.36f, cx + w * 0.05f, cy + h * 0.36f), p)
            }
            "bee" -> {
                p.color = Color.parseColor("#FFFFFF")
                cv.drawOval(RectF(cx - w * 0.34f, cy - h * 0.48f, cx - w * 0.02f, cy - h * 0.14f), p)
                cv.drawOval(RectF(cx + w * 0.02f, cy - h * 0.48f, cx + w * 0.34f, cy - h * 0.14f), p)
                p.color = Color.parseColor("#FFD66B")
                cv.drawCircle(cx, cy + h * 0.06f, w * 0.32f, p)
                p.color = Color.parseColor("#1A1A1A")
                p.strokeWidth = w * 0.07f
                p.style = Paint.Style.STROKE
                cv.drawLine(cx - w * 0.24f, cy - h * 0.02f, cx + w * 0.24f, cy - h * 0.02f, p)
                cv.drawLine(cx - w * 0.28f, cy + h * 0.16f, cx + w * 0.28f, cy + h * 0.16f, p)
                p.style = Paint.Style.FILL
                eye(cx - w * 0.10f, cy - h * 0.10f, w * 0.045f)
                eye(cx + w * 0.10f, cy - h * 0.10f, w * 0.045f)
            }
            "bird" -> {
                p.color = Color.parseColor("#5BA8F5")
                cv.drawCircle(cx, cy, w * 0.36f, p)
                p.color = Color.parseColor("#3D7AC2")
                cv.drawOval(RectF(cx - w * 0.30f, cy + h * 0.02f, cx + w * 0.05f, cy + h * 0.28f), p)
                p.color = Color.parseColor("#FF9F43")
                val beak = Path()
                beak.moveTo(cx + w * 0.28f, cy - h * 0.04f)
                beak.lineTo(cx + w * 0.48f, cy + h * 0.04f)
                beak.lineTo(cx + w * 0.28f, cy + h * 0.10f)
                beak.close()
                cv.drawPath(beak, p)
                eye(cx + w * 0.12f, cy - h * 0.12f, w * 0.05f)
            }
            "rabbit" -> {
                p.color = Color.parseColor("#C9B8A3")
                cv.drawOval(RectF(cx - w * 0.24f, cy - h * 0.48f, cx - w * 0.04f, cy - h * 0.06f), p)
                cv.drawOval(RectF(cx + w * 0.04f, cy - h * 0.48f, cx + w * 0.24f, cy - h * 0.06f), p)
                p.color = Color.parseColor("#E8DCC8")
                cv.drawCircle(cx, cy + h * 0.10f, w * 0.32f, p)
                eye(cx - w * 0.12f, cy + h * 0.04f, w * 0.045f)
                eye(cx + w * 0.12f, cy + h * 0.04f, w * 0.045f)
            }
            "cat" -> {
                p.color = Color.parseColor("#F5A623")
                val earL = Path()
                earL.moveTo(cx - w * 0.30f, cy - h * 0.10f)
                earL.lineTo(cx - w * 0.34f, cy - h * 0.44f)
                earL.lineTo(cx - w * 0.06f, cy - h * 0.28f)
                earL.close()
                val earR = Path()
                earR.moveTo(cx + w * 0.30f, cy - h * 0.10f)
                earR.lineTo(cx + w * 0.34f, cy - h * 0.44f)
                earR.lineTo(cx + w * 0.06f, cy - h * 0.28f)
                earR.close()
                cv.drawPath(earL, p)
                cv.drawPath(earR, p)
                cv.drawCircle(cx, cy + h * 0.06f, w * 0.32f, p)
                eye(cx - w * 0.12f, cy, w * 0.045f)
                eye(cx + w * 0.12f, cy, w * 0.045f)
                p.color = Color.parseColor("#E88CA0")
                cv.drawCircle(cx, cy + h * 0.14f, w * 0.04f, p)
            }
            "fox" -> {
                p.color = Color.parseColor("#E8734A")
                val earL = Path()
                earL.moveTo(cx - w * 0.30f, cy - h * 0.10f)
                earL.lineTo(cx - w * 0.36f, cy - h * 0.46f)
                earL.lineTo(cx - w * 0.08f, cy - h * 0.28f)
                earL.close()
                val earR = Path()
                earR.moveTo(cx + w * 0.30f, cy - h * 0.10f)
                earR.lineTo(cx + w * 0.36f, cy - h * 0.46f)
                earR.lineTo(cx + w * 0.08f, cy - h * 0.28f)
                earR.close()
                cv.drawPath(earL, p)
                cv.drawPath(earR, p)
                cv.drawCircle(cx, cy + h * 0.06f, w * 0.32f, p)
                p.color = Color.parseColor("#FFF3E0")
                cv.drawOval(RectF(cx - w * 0.20f, cy + h * 0.08f, cx + w * 0.20f, cy + h * 0.34f), p)
                eye(cx - w * 0.12f, cy, w * 0.045f)
                eye(cx + w * 0.12f, cy, w * 0.045f)
                p.color = Color.parseColor("#1A1A1A")
                cv.drawCircle(cx, cy + h * 0.14f, w * 0.04f, p)
            }
            "deer" -> {
                p.color = Color.parseColor("#8B5E3C")
                p.strokeWidth = w * 0.05f
                p.style = Paint.Style.STROKE
                cv.drawLine(cx - w * 0.16f, cy - h * 0.22f, cx - w * 0.30f, cy - h * 0.46f, p)
                cv.drawLine(cx - w * 0.30f, cy - h * 0.46f, cx - w * 0.40f, cy - h * 0.38f, p)
                cv.drawLine(cx + w * 0.16f, cy - h * 0.22f, cx + w * 0.30f, cy - h * 0.46f, p)
                cv.drawLine(cx + w * 0.30f, cy - h * 0.46f, cx + w * 0.40f, cy - h * 0.38f, p)
                p.style = Paint.Style.FILL
                p.color = Color.parseColor("#C89B6D")
                cv.drawOval(RectF(cx - w * 0.30f, cy - h * 0.22f, cx + w * 0.30f, cy + h * 0.42f), p)
                eye(cx - w * 0.12f, cy + h * 0.02f, w * 0.045f)
                eye(cx + w * 0.12f, cy + h * 0.02f, w * 0.045f)
                p.color = Color.parseColor("#5B4636")
                cv.drawOval(RectF(cx - w * 0.10f, cy + h * 0.22f, cx + w * 0.10f, cy + h * 0.34f), p)
            }
        }
    }
}

/** Mimi the focus pet: a sitting cat. Eyes change with mood. */
class PetView(ctx: Context, private val mood: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val bodyC = Color.parseColor("#F5A623")
        val cream = Color.parseColor("#FFF3E0")
        val pink = Color.parseColor("#E88CA0")
        val dark = Color.parseColor("#1A1A1A")

        // tail
        p.style = Paint.Style.STROKE
        p.color = bodyC
        p.strokeWidth = w * 0.045f
        p.strokeCap = Paint.Cap.ROUND
        val tail = Path()
        tail.moveTo(cx + w * 0.26f, h * 0.88f)
        tail.quadTo(cx + w * 0.42f, h * 0.88f, cx + w * 0.40f, h * 0.68f)
        cv.drawPath(tail, p)

        // body (sitting)
        p.style = Paint.Style.FILL
        p.color = bodyC
        cv.drawOval(RectF(cx - w * 0.28f, h * 0.52f, cx + w * 0.28f, h * 0.92f), p)
        // belly
        p.color = cream
        cv.drawOval(RectF(cx - w * 0.16f, h * 0.60f, cx + w * 0.16f, h * 0.90f), p)
        // paws
        p.color = cream
        cv.drawOval(RectF(cx - w * 0.24f, h * 0.84f, cx - w * 0.06f, h * 0.92f), p)
        cv.drawOval(RectF(cx + w * 0.06f, h * 0.84f, cx + w * 0.24f, h * 0.92f), p)

        // ears
        p.color = bodyC
        val earL = Path()
        earL.moveTo(cx - w * 0.24f, h * 0.30f)
        earL.lineTo(cx - w * 0.30f, h * 0.08f)
        earL.lineTo(cx - w * 0.06f, h * 0.20f)
        earL.close()
        val earR = Path()
        earR.moveTo(cx + w * 0.24f, h * 0.30f)
        earR.lineTo(cx + w * 0.30f, h * 0.08f)
        earR.lineTo(cx + w * 0.06f, h * 0.20f)
        earR.close()
        cv.drawPath(earL, p)
        cv.drawPath(earR, p)
        p.color = pink
        val inL = Path()
        inL.moveTo(cx - w * 0.21f, h * 0.27f)
        inL.lineTo(cx - w * 0.25f, h * 0.13f)
        inL.lineTo(cx - w * 0.10f, h * 0.21f)
        inL.close()
        val inR = Path()
        inR.moveTo(cx + w * 0.21f, h * 0.27f)
        inR.lineTo(cx + w * 0.25f, h * 0.13f)
        inR.lineTo(cx + w * 0.10f, h * 0.21f)
        inR.close()
        cv.drawPath(inL, p)
        cv.drawPath(inR, p)

        // head
        p.color = bodyC
        cv.drawCircle(cx, h * 0.38f, w * 0.27f, p)
        // muzzle
        p.color = cream
        cv.drawOval(RectF(cx - w * 0.14f, h * 0.42f, cx + w * 0.14f, h * 0.56f), p)

        // eyes depend on mood
        val eyeY = h * 0.34f
        val eyeDx = w * 0.11f
        when (mood) {
            "happy", "proud" -> {
                // happy closed arcs (upside-down U)
                p.color = dark
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.018f
                cv.drawArc(RectF(cx - eyeDx - w * 0.05f, eyeY - h * 0.03f, cx - eyeDx + w * 0.05f, eyeY + h * 0.03f), 180f, 180f, false, p)
                cv.drawArc(RectF(cx + eyeDx - w * 0.05f, eyeY - h * 0.03f, cx + eyeDx + w * 0.05f, eyeY + h * 0.03f), 180f, 180f, false, p)
                p.style = Paint.Style.FILL
            }
            "sleepy" -> {
                // sleepy closed curves (downward arcs)
                p.color = dark
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.018f
                cv.drawArc(RectF(cx - eyeDx - w * 0.05f, eyeY - h * 0.01f, cx - eyeDx + w * 0.05f, eyeY + h * 0.05f), 0f, 180f, false, p)
                cv.drawArc(RectF(cx + eyeDx - w * 0.05f, eyeY - h * 0.01f, cx + eyeDx + w * 0.05f, eyeY + h * 0.05f), 0f, 180f, false, p)
                p.style = Paint.Style.FILL
                // "Z" for sleep
                p.color = C.muted
                p.textSize = w * 0.10f
                p.textAlign = Paint.Align.LEFT
                cv.drawText("z", cx + w * 0.30f, h * 0.16f, p)
                cv.drawText("z", cx + w * 0.36f, h * 0.10f, p)
            }
            else -> {
                p.color = dark
                cv.drawCircle(cx - eyeDx, eyeY, w * 0.035f, p)
                cv.drawCircle(cx + eyeDx, eyeY, w * 0.035f, p)
            }
        }
        // nose + mouth
        p.color = pink
        val nose = Path()
        nose.moveTo(cx - w * 0.035f, h * 0.44f)
        nose.lineTo(cx + w * 0.035f, h * 0.44f)
        nose.lineTo(cx, h * 0.49f)
        nose.close()
        cv.drawPath(nose, p)
        p.color = dark
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.012f
        cv.drawLine(cx, h * 0.49f, cx, h * 0.52f, p)
        p.style = Paint.Style.FILL
        // whiskers
        p.color = Color.parseColor("#8A6A3B")
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.008f
        for (s in listOf(-1f, 1f)) {
            cv.drawLine(cx + s * w * 0.10f, h * 0.46f, cx + s * w * 0.30f, h * 0.42f, p)
            cv.drawLine(cx + s * w * 0.10f, h * 0.50f, cx + s * w * 0.30f, h * 0.50f, p)
        }
        p.style = Paint.Style.FILL
    }
}

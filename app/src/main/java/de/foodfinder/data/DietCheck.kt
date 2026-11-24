package de.foodfinder.data

/**
 * Decides per dish whether it is vegetarian / vegan. Only the dish counts, never the restaurant's type:
 * explicit service labels first, then the dish name/description.
 */
object DietCheck {
    private val vegMarkers = listOf("vegan", "vegetar", "veggie", "vegi ", "tofu", "seitan", "tempeh", "plant-based", "plant based", "pflanzlich", "beyond", "fleischlos", "ohne fleisch")
    private val veganMarkers = listOf("vegan", "pflanzlich", "plant-based", "plant based")
    private val meat = listOf(
        "hähnchen", "haehnchen", "hühnchen", "huhn", "chicken", "pollo", "pute", "truthahn", "turkey", "rind", "beef", "manzo", "kalb", "veal",
        "schwein", "pork", "maiale", "speck", "bacon", "schinken", "prosciutto", "salami", "wurst", "würst", "sucuk", "chorizo", "pastrami",
        "döner", "doener", "kebab", "kebap", "gyros", "hack", "lamm", "lamb", "ente", "duck", "steak", "fleisch", "meat", "carne", "nuggets",
        "wings", "pulled", "thunfisch", "tuna", "tonno", "lachs", "salmon", "sake ", "fisch", "fish", "garnele", "shrimp", "scampi", "gamber",
        "krabbe", "crab", "calamari", "tintenfisch", "sardell", "anchov", "meeresfrücht", "seafood", "muschel", "frutti di mare", "unagi", "ebi",
        "katsu", "teriyaki chicken", "burger", "smash", "patty", "nugget", "bbq", "mortadella", "'nduja", "nduja", "bulgogi", "wiener", "frikadelle", "leberkäse", "cevapcici", "köfte", "kofte", "meatball", "hot dog", "pepperoni pizza",
    )
    private val vegetarianDishes = listOf(
        "margherita", "margarita", "funghi", "quattro formaggi", "4 käse", "vier käse", "caprese", "spinat", "gemüse", "verdure", "vegetable",
        "falafel", "halloumi", "paneer", "käsespätzle", "pilz", "mushroom", "aglio e olio", "arrabbiata", "pomodoro", "napoli", "pesto",
        "mozzarella", "burrata", "avocado", "kappa", "kürbis", "pumpkin", "linsen", "dal ", "dhal", "chana", "aloo", "palak", "pommes", "fries",
        "camembert", "feta", "ziegenkäse", "frühlingsrolle", "spring roll", "edamame", "inari", "kimchi", "gnocchi", "risotto", "bruschetta",
    )

    private fun text(i: MenuItem) = (i.name + " " + i.description).lowercase()

    fun isVegan(i: MenuItem): Boolean {
        if (i.labels.any { it.contains("vegan", true) }) return true
        val t = text(i)
        return veganMarkers.any { t.contains(it) } && !t.contains("nicht vegan") && !t.contains("non-vegan")
    }

    fun isVegetarian(i: MenuItem): Boolean {
        if (isVegan(i)) return true
        if (i.labels.any { it.contains("vegetar", true) || it.contains("veggie", true) }) return true
        val name = i.name.lowercase()
        if (vegMarkers.any { name.contains(it) }) return true
        val t = text(i)
        if (meat.any { t.contains(it) }) return false
        return vegMarkers.any { t.contains(it) } || vegetarianDishes.any { t.contains(it) }
    }

    fun matches(i: MenuItem, d: Diet) = when (d) {
        Diet.ANY -> true
        Diet.VEGETARIAN -> isVegetarian(i)
        Diet.VEGAN -> isVegan(i)
    }

    fun tagsMatch(tags: List<String>, d: Diet) = when (d) {
        Diet.ANY -> true
        Diet.VEGETARIAN -> tags.any { it.contains("VEGETAR", true) || it.contains("VEGAN", true) }
        Diet.VEGAN -> tags.any { it.contains("VEGAN", true) }
    }

    fun textMatches(text: String, d: Diet): Boolean {
        val t = text.lowercase()
        return when (d) {
            Diet.ANY -> true
            Diet.VEGAN -> t.contains("vegan")
            Diet.VEGETARIAN -> vegMarkers.any { t.contains(it) }
        }
    }
}

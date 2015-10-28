class D(val a: String, val b: Boolean)

fun foo(p: Boolean, v: D?): String {
    if (p && v!!.b) return v.a
    return ""
} 

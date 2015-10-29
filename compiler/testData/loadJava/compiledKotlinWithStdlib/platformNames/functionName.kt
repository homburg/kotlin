package test

annotation class A(val s: String)

@JvmName("bar")
@A("1")
fun foo() = "foo"

@field:A("2")
var v: Int = 1
    @JvmName("vget")
    @A("3")
    get
    @JvmName("vset")
    @A("4")
    set

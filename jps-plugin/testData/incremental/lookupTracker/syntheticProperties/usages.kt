package foo.bar

import /*p:<root>*/JavaClass
import foo./*p:foo*/KotlinClass

/*p:foo.bar*/fun test() {
    val j = /*p:foo.bar p:java.lang p:kotlin.annotation*/JavaClass()
    val k = /*p:foo.bar p:java.lang p:kotlin.annotation*/KotlinClass()

    j./*c:JavaClass*/getFoo()
    j./*c:JavaClass p:foo.bar p:java.lang p:kotlin.annotation c:JavaClass(getSetFoo) c:JavaClass(getSETFoo)*/setFoo(2)
    j./*c:JavaClass p:foo.bar c:JavaClass(getFoo) c:JavaClass(getFOO) c:JavaClass(setFoo)*/foo = 2
    j./*c:JavaClass p:foo.bar c:JavaClass(getFoo) c:JavaClass(getFOO) c:JavaClass(setFoo)*/foo
    j./*c:JavaClass p:foo.bar c:JavaClass(getBar) c:JavaClass(getBAR) c:JavaClass(setBar)*/bar
    j./*c:JavaClass p:foo.bar c:JavaClass(getBar) c:JavaClass(getBAR) c:JavaClass(setBar)*/bar = ""
    j./*c:JavaClass p:foo.bar c:JavaClass(getBazBaz) c:JavaClass(getBAZBaz) p:java.lang p:kotlin.annotation*/bazBaz
    j./*c:JavaClass p:foo.bar c:JavaClass(getBazBaz) c:JavaClass(getBAZBaz) p:java.lang p:kotlin.annotation*/bazBaz = ""
    j./*c:JavaClass*/setBoo(2)
    j./*c:JavaClass p:foo.bar c:JavaClass(getBoo) c:JavaClass(getBOO) p:java.lang p:kotlin.annotation*/boo = 2
    k./*c:foo.KotlinClass*/getFoo()
    k./*c:foo.KotlinClass*/setFoo(2)
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getFoo) c:foo.KotlinClass(getFOO) c:foo.KotlinClass(setFoo)*/foo = 2
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getFoo) c:foo.KotlinClass(getFOO) c:foo.KotlinClass(setFoo)*/foo
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getBar) c:foo.KotlinClass(getBAR) c:foo.KotlinClass(setBar)*/bar
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getBar) c:foo.KotlinClass(getBAR) c:foo.KotlinClass(setBar)*/bar = ""
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getBazBaz) c:foo.KotlinClass(getBAZBaz) p:java.lang p:kotlin.annotation*/bazBaz
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getBazBaz) c:foo.KotlinClass(getBAZBaz) p:java.lang p:kotlin.annotation*/bazBaz = ""
    k./*c:foo.KotlinClass*/setBoo(2)
    k./*c:foo.KotlinClass p:foo.bar c:foo.KotlinClass(getBoo) c:foo.KotlinClass(getBOO) p:java.lang p:kotlin.annotation*/boo = 2
}

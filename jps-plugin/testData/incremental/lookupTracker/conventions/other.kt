package foo.bar

/*p:foo.bar*/fun testOther(a: /*p:foo.bar*/A, b: /*p:foo.bar*/Int, c: /*p:foo.bar*/Any, na: /*p:foo.bar*/A?) {
    /*c:foo.bar.A(set) p:foo.bar(set) p:java.lang(set) p:kotlin.annotation(set) c:foo.bar.A(getSet) c:foo.bar.A(getSET)*/a[1] = /*c:foo.bar.A(get) p:foo.bar(get) p:java.lang(get) p:kotlin.annotation(get) c:foo.bar.A(getGet) c:foo.bar.A(getGET)*/a[2]

    b /*c:foo.bar.A(contains) p:foo.bar(contains) p:java.lang(contains) p:kotlin.annotation(contains) c:foo.bar.A(getContains) c:foo.bar.A(getCONTAINS)*/in a
    "s" /*c:foo.bar.A(contains) p:foo.bar(contains) p:java.lang(contains) p:kotlin.annotation(contains) c:foo.bar.A(getContains) c:foo.bar.A(getCONTAINS)*/!in a

    /*c:foo.bar.A(invoke) p:foo.bar(invoke) p:java.lang(invoke) p:kotlin.annotation(invoke)*/a()
    /*c:foo.bar.A(invoke) p:foo.bar(invoke) p:java.lang(invoke) p:kotlin.annotation(invoke)*/a(1)

    val (/*c:foo.bar.A(component1) p:foo.bar(component1) p:java.lang(component1) p:kotlin.annotation(component1) c:foo.bar.A(getComponent1)*/h, /*c:foo.bar.A(component2) p:foo.bar(component2) p:java.lang(component2) p:kotlin.annotation(component2) c:foo.bar.A(getComponent2)*/t) = a;

    for ((/*c:foo.bar.A(component1) p:foo.bar(component1) p:java.lang(component1) p:kotlin.annotation(component1) c:foo.bar.A(getComponent1)*/f, /*c:foo.bar.A(component2) p:foo.bar(component2) p:java.lang(component2) p:kotlin.annotation(component2) c:foo.bar.A(getComponent2)*/s) in /*c:foo.bar.A(iterator) c:foo.bar.A(hasNext) p:foo.bar(hasNext) p:java.lang(hasNext) p:kotlin.annotation(hasNext) c:foo.bar.A(next)*/a);
    for ((/*c:foo.bar.A(component1) p:foo.bar(component1) p:java.lang(component1) p:kotlin.annotation(component1) c:foo.bar.A(getComponent1)*/f, /*c:foo.bar.A(component2) p:foo.bar(component2) p:java.lang(component2) p:kotlin.annotation(component2) c:foo.bar.A(getComponent2)*/s) in /*c:foo.bar.A(iterator) p:foo.bar(iterator) p:java.lang(iterator) p:kotlin.annotation(iterator) c:foo.bar.A(hasNext) p:foo.bar(hasNext) p:java.lang(hasNext) p:kotlin.annotation(hasNext) c:foo.bar.A(next)*/na);
}

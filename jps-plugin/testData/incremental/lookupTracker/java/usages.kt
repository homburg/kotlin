package foo

import bar./*p:bar*/C
import baz.*

/*p:foo*/fun usages() {
    val c = /*p:foo p:baz p:java.lang p:kotlin.annotation*/C()

    c./*c:bar.C*/field
    c./*c:bar.C*/field = 2
    c./*c:bar.C*/func()
    c./*c:bar.C*/B()

    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/sfield
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/sfield = "new"
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/sfunc()
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/S()

    // inherited from I
    c./*c:bar.C*/ifunc()
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/isfield
    // expected error: Unresolved reference: IS
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/C./*c:bar.C*/IS()


    val i: /*p:foo*/I = c
    i./*c:foo.I*/ifunc()

    /*p:foo p:baz p:java.lang p:kotlin.annotation*/I./*c:foo.I*/isfield
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/I./*c:foo.I*/IS()

    /*p:foo p:baz p:java.lang p:kotlin.annotation*/E./*c:baz.E*/F
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/E./*c:baz.E*/F./*c:baz.E*/field
    /*p:foo p:baz p:java.lang p:kotlin.annotation*/E./*c:baz.E*/S./*c:baz.E*/func()
}

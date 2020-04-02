class C(val map: MutableMap<String, Any>) {
    var foo by <!AMBIGUITY!>map<!>
}

var bar by <!AMBIGUITY!>hashMapOf<String, Any>()<!>
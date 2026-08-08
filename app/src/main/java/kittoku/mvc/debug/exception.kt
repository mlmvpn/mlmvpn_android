package kittoku.mvc.debug


internal fun assertAlways(value: Boolean) {
    if (!value) {
        throw AssertionError()
    }
}
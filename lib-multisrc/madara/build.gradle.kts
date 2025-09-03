plugins {
    id("lib-multisrc")
}

baseVersionCode = 2

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}

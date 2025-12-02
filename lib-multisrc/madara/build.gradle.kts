plugins {
    id("lib-multisrc")
}

baseVersionCode = 4

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}

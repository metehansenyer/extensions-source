plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}

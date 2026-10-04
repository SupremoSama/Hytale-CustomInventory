rootProject.name = "CustomInventory"
// The core builds independently when the optional example is absent.
if (file("example-extension").isDirectory) include("example-extension")

The published builds are signed with a key kept by the author, `local.jks`, which is not in git.
A build made without it is signed with Android's own debug key instead. Both install fine, but
Android only updates an app with the key it was first installed with: to switch between the two,
uninstall the extension once (`adb uninstall local.karoo.gtavimap`) and install the other build.

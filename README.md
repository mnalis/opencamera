## About

This OpenCamera was forked from https://sourceforge.net/p/opencamera/code/ci/master/tree/
It changes default camera ID from 0 (upstream default) to 3 (wide-angle camera on my phone).

## History

In order to change hardcoded default camera, I had to make a small code fix first.
It is included in this repository, but as of 2023-06-24 it is not yet in upstream,
see https://sourceforge.net/p/opencamera/tickets/1059/ for details

## User-configurable camera in settings?
I also suggested making default camera ID configurable in that ticket mentioned above, so you can go and add your support!

## How to change to different hardcoded default camera?
If your phone users different ID for wide-angle camera, or you want to make some other camera default, here are instructions how to do it
from your web browser, without any programming knowledge required or any installations on your computer needed!

- fork this repository (log in to GitHub, navigate to this repository and click on `Fork` / `Create new fork` button
- in **your fork**, navigate to file `app/src/main/java/net/sourceforge/opencamera/MyApplicationInterface.java` and open it
- click on `Edit this file` (pen icon)
- find the line saying `private final static int cameraId_default = 3;` (currently around line `150`) and change that `3` to whatever camera ID you want
- click `Commit changes`, write a short description what you changed and why, and confirm
- click on the `Actions` GitHub button in **your fork**
- click on `Build debug APK`
- click on `Run workflow` and confirm (you do not need to select different branch if above you commited directly to your `master` branch)
- wait about 10 minutes until it completes with green checkmark
- click on that box which has green checkbox for your latest workflow
- there will be section named `Artifacts` / `Produced during runtime` with file named `debug-apk`, click on it to download
- `debug-apk.zip` is downloaded. Unpack it, and there is your `.apk` file
- transfer the file to your phone (using whatever method you like) and click on it to install (Android might ask you to allow installing from that source, answer _yes_ - you'll probably need to uninstall your previos OpenCamera if it came from another source)

 That's it, you new OpenCamera will use new default camera ID. While it is not as hard as it looks, it would be nicer if it was user-configurable directly in the app, so see section above on how to add your support for that!
 

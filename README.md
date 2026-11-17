# Neon Volley

Android brick-breaker. Drag to aim, release to fire a volley of balls at numbered bricks.
Each number is the hits a brick still needs. After every volley the bricks move down one row;
the game ends when a brick reaches the floor.

- Green rings: +1 ball for the rest of the game.
- Gold markers (from round 4): a ball passing through fires a beam along that row or column.
- Tap during a volley to fast-forward. Long volleys also speed up by themselves.
- The bars at the top centre switch sound on and off.
- Your game is saved at the start of every round, so you can close the app and continue later.

Plain Java, one Activity, no libraries, no audio or image files. Works on Android 5.0 and newer.

## Build the APK with GitHub

1. On github.com click **+** > **New repository**, name it `neon-volley`, choose **Public**, click **Create repository**.
2. Click **uploading an existing file**. Unzip `NeonVolley.zip`, open the `NeonVolley` folder, select everything
   inside it and drag it into the page. GitHub skips the `.github` folder ("This file is hidden"); that is expected.
   Click **Commit changes**.
3. Click **Add file > Create new file**. As the name type `.github/workflows/build.yml`, then paste the
   contents of that same file from the zip (open it with Notepad). Click **Commit changes**.
4. Open the **Actions** tab and wait for the green tick (3 to 5 minutes).
5. On the repository's front page open **Releases** and download **NeonVolley.apk**.

## Install on the phone

1. In Chrome on the phone open `https://github.com/YOUR-USERNAME/neon-volley/releases` and tap **NeonVolley.apk**.
2. Open the downloaded file. If Android says the browser may not install unknown apps, tap **Settings**,
   switch on **Allow from this source**, go back and tap **Install**.
3. If Play Protect warns about an unknown developer, tap **More details > Install anyway**.

## Pinned versions

Gradle 8.9, Android Gradle Plugin 8.5.2, JDK 17, compileSdk/targetSdk 34, minSdk 21.
There is no Gradle wrapper; the workflow installs Gradle itself.

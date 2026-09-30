# Setup guide: Mac mini image server and connector accounts

*Companion to [spec.md](spec.md) and [web-prototype-plan.md](web-prototype-plan.md). Date: 2026-09-30.*

Google's developer pages could not be opened while writing this, so menu names in the Google Cloud console may differ slightly from what is written here. The Maven steps for `petsciiator` were read from that project's own install scripts (Windows `.cmd` files) and translated for macOS; they have not been run on a Mac.

What each step is for:

| Step | Needed for |
|---|---|
| A. Mac mini | Everything |
| B. Relay page on ursiny.cz | Only the Google Photos connector (Google sign-in) |
| C. Google Cloud registration | Only the Google Photos connector |
| C2. Gemini API key | Only the Nano Banana connector |
| D. Credentials and config file | Everything (fill in only what your channels use) |

The local folder and Wikipedia connectors need only step A and the config file (step D). **Suggested order:**

1. **A and D** with a local folder channel. This is enough for the first build steps of the [prototype plan](web-prototype-plan.md).
2. **B and C** when the Google Photos connector is built, and **C2** when the Nano Banana connector is built. Each is independent of the others and can be skipped if you don't want that connector.

## A. Prepare the Mac mini (about 30-45 min)

1. **Tools.** Install Homebrew if needed, then:
   ```
   brew install openjdk@17 maven git
   ```
   Follow the note Homebrew prints so that `java -version` shows 17.

2. **Pick one working folder** for all three projects, for example `~/dev`. It doesn't matter where, and nothing depends on the folders being next to each other. `mvn install` copies the built libraries into `~/.m2`, and this repo finds them there.
   ```
   mkdir -p ~/dev && cd ~/dev
   git clone https://github.com/EgonOlsen71/basicv2.git
   git clone https://github.com/EgonOlsen71/petsciiator.git
   git clone https://github.com/ursimon/ImageViewer.git
   cd ImageViewer && git checkout claude/trusting-noether-wk4jil && cd ..
   ```

3. **Build `basicv2`** (this repo's `pom.xml` depends on it):
   ```
   cd ~/dev/basicv2 && mvn clean install
   ```
   If it fails, look at its `build.cmd` for the intended Maven call and send me the error.

4. **Build `petsciiator`.** It ships its own copy of the TwelveMonkeys image libraries, which must be installed first. These are the commands from its `install_ext_libs.cmd`, without the Windows `call`:
   ```
   cd ~/dev/petsciiator
   mvn install:install-file -Dfile=lib/twelvemonkeys-imageio-core-3.11.0-SNAPSHOT.jar -DgroupId=com.twelvemonkeys.imageio -DartifactId=imageio-core -Dversion=3.11.0 -Dpackaging=jar -DgeneratePom=true
   mvn install:install-file -Dfile=lib/twelvemonkeys-common-image-3.11.0-SNAPSHOT.jar -DgroupId=com.twelvemonkeys.imageio -DartifactId=imageio-image -Dversion=3.11.0 -Dpackaging=jar -DgeneratePom=true
   mvn install:install-file -Dfile=lib/twelvemonkeys-imageio-metadata-3.11.0-SNAPSHOT.jar -DgroupId=com.twelvemonkeys.imageio -DartifactId=imageio-metadata -Dversion=3.11.0 -Dpackaging=jar -DgeneratePom=true
   mvn install:install-file -Dfile=lib/twelvemonkeys-imageio-webp-3.11.0-SNAPSHOT.jar -DgroupId=com.twelvemonkeys.imageio -DartifactId=imageio-webp -Dversion=3.11.0 -Dpackaging=jar -DgeneratePom=true
   mvn install:install-file -Dfile=lib/twelvemonkeys-common-lang-3.11.0-SNAPSHOT.jar -DgroupId=com.twelvemonkeys.imageio -DartifactId=imageio-common -Dversion=3.11.0 -Dpackaging=jar -DgeneratePom=true
   mvn clean package install
   ```
   The last line is the Maven part of its `install_mvn.cmd`. Skip the `xcopy` line that follows it there; it only copies the jar for Windows users.

5. **Build this repo:**
   ```
   cd ~/dev/ImageViewer && mvn clean package
   ```
   This builds the code that exists today; the new server isn't written yet. The point is to prove that Java, Maven and the two libraries work on this Mac. It downloads other libraries from the internet, so the Mac must be online. If it fails, send me the error. A missing artifact means one of steps 3-4 didn't complete.

6. **Give the Mac a fixed address.** In your router, reserve an IP for the Mac mini (a "DHCP reservation"). Find the current address with `ipconfig getifaddr en0` (Wi-Fi is often `en1`) and write it down, for example `192.168.1.50`. Reasons: you type this address into the C64 once, and it is saved; QR codes on message images point to it; and the relay page (step B) forwards to it. See "Why a fixed address" below.

7. **Sleep and firewall.** In System Settings set the Mac to never sleep while the server runs. The first time the server starts, allow Java to accept incoming connections when macOS asks, or neither the C64 nor your phone can reach it.

## B. Prepare the relay page on ursiny.cz (about 15 min)

Only for the Google Photos connector.

1. Choose a public HTTPS address, for example `https://ursiny.cz/gp/callback.html`.
2. Tell me the Mac's fixed address and the port (8080). I write the roughly 10-line page. It forwards Google's answer (the one-time code and the `state` value that tells the server which channel started the sign-in) only to that address, and you upload it.
3. Open the page on your phone over mobile data to check that it loads.

## C. Register with Google (about 30 min)

Only for the Google Photos connector.

1. Go to console.cloud.google.com, signed in with the Google account whose photos you will view. Create a project, for example "c64-photos".
2. **APIs & Services → Library:** search for **Google Photos Picker API** and click Enable.
3. **OAuth consent screen** (also called "Google Auth Platform"):
   - App name; your email as support and developer contact.
   - Audience **External**, publishing status **Testing**.
   - Add your own Google account under **Test users**.
   - Under Data access, add the scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
   - If the console asks for authorized domains, add `ursiny.cz`.
4. **Credentials → Create credentials → OAuth client ID:**
   - Type **Web application**.
   - Authorised redirect URI, exactly as your relay page address: `https://ursiny.cz/gp/callback.html`.
   - Copy the **client ID** and **client secret**.

## C2. Gemini API key for the Nano Banana connector (about 10 min, optional)

1. Go to Google AI Studio (aistudio.google.com), signed in with any Google account.
2. Create an API key. Image generation is normally paid per picture (a free tier may exist; check the current terms), so if it asks for billing, enable it and set a spending alert in Google Cloud.
3. Note the current image model id and its price (see [spec.md](spec.md) §5.2). The server's `maxPerDay` setting is a second safety cap.

## D. Secrets and config file on the Mac (about 15 min)

1. Put the secrets in a private file outside the repository, for example `~/c64server/secrets.env`. Leave out the ones you don't use yet:
   ```
   export GOOGLE_CLIENT_ID=...
   export GOOGLE_CLIENT_SECRET=...
   export GEMINI_API_KEY=...
   export ADMIN_PASSWORD=...
   ```
   `ADMIN_PASSWORD` is optional; without it the admin page is open to your home network. Never commit this file or paste a secret into a chat. The server will read these values from the environment, so before starting it you run `source ~/c64server/secrets.env`; I'll provide the start script with the server.
2. **Make a photo folder** for the first channel, for example `~/Pictures/C64`, and put 10-20 pictures in it. Use a mix of JPEG and PNG, and of portrait and landscape shots, because that is what lets you judge the `fit`, `center` and `smart` crop modes.
3. **Create `~/c64server/config.yaml`.** The full example with every connector is in [spec.md](spec.md) §4. Start with this and add channels as their connectors are built:
   ```yaml
   server:
     port: 8080
     publicBaseUrl: http://192.168.1.50:8080   # your Mac's fixed address
     contact: you@example.com                  # used in the User-Agent of Wikipedia requests
   playlist:
     mode: rotate
     onProblem: message
   channels:
     scans:
       type: local-folder
       enabled: true
       path: /Users/<you>/Pictures/C64
       order: shuffle
   ```
   There is no display interval and no channel choice on the C64 side. The C64 sets its own interval, and the server's playlist decides which channel supplies each image.

## E. What to send back

- The Mac's fixed address, and the folder path you used for the first channel.
- The relay page address you chose (if you do Google Photos).
- Your Java version (`java -version`) and whether steps A3, A4 and A5 worked (paste any error).
- Whether Google accepted the redirect URI when you saved the client (if you did step C).
- Whether you created a Gemini key (step C2), and which image model it offers.

## What to expect at the first Google sign-in (Google Photos connector only)

- Google shows "app not verified". Choose Advanced, then continue.
- In Testing mode the sign-in expires after about 7 days. The server then marks the Google Photos channel "needs action", and the C64 shows a message image with a QR code to the admin page, where you sign in again.

## Why a fixed address (and what about mDNS)

Two different programs have to find the Mac:

| Who | How it finds the Mac | Needs |
|---|---|---|
| Phone browser (admin page, QR code on a message image, forwarded callback) | A normal navigation to `http://<address>:8080/...` | An IP works. A `.local` name usually works on iPhone and Mac; Android is less reliable. |
| C64 through the WiC64 | The server address you type in on the C64's first run, which is saved to its disk | An IP works. Whether the WiC64 firmware resolves `.local` (mDNS) names is **not confirmed**. |

A DHCP reservation costs nothing and works for both, so it is the default. A `.local` name can be tested later from the WiC64 as an option; it would let the relay page and the C64 survive an address change without editing. Google's rule against `.local` applies only to the redirect URI, which is the `ursiny.cz` page, not to where that page forwards.

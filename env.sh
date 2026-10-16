# source this before building from WSL
export JAVA_HOME="$(ls -d $HOME/.jdks/jdk-17* | head -1)"
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/gradle/gradle-8.14.3/bin:$PATH"
# WSL adb client -> Windows adb server (USB devices). Start the server from WSL with:
#   /mnt/c/Users/hocme/platform-tools/adb.exe -a -P 5037 nodaemon server &
export ADB_SERVER_SOCKET="tcp:$(ip route | awk '/default/ {print $3}'):5037"

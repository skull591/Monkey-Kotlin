echo $1
adb shell CLASSPATH=/data/local/tmp/ComboDroid.jar /system/bin/app_process /data/local/tmp edu.nju.ics.alex.combodroid.monkey.MonkeyKt $1

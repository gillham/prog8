#/bin/env sh

IL65_LIBDIR=../lib65
IL65CLASSPATH=out/production/il65
LIBJARS=/opt/irmen/idea-2018/plugins/Kotlin/lib/kotlin-stdlib.jar:antlr/lib/antlr-runtime-4.7.1.jar 

java -Dil65.libdir=${IL65_LIBDIR} -cp ${IL65CLASSPATH}:${LIBJARS} il65.MainKt $*

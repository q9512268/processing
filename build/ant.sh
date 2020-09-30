#!/bin/sh
JAVA_HOME="/Users/christianluetticke/Documents/WIKI/JAVA/jdk1.8.0_202.jdk/Contents/Home"; export JAVA_HOME
export PATH=$JAVA_HOME/bin:$PATH
ANT_HOME="/Users/christianluetticke/Documents/WIKI/ANT/apache-ant-1.10.8"; export ENT_HOME
export PATH=$ANT_HOME/bin:$PATH

ant $@
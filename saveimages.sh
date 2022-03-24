#!/bin/bash

rm -rf img
mkdir -p img

for IMG in $(sqlite3 devices.db "SELECT ImgURL FROM devices"); do
	wget -O img/$(basename $IMG) $IMG
done

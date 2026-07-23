@echo off
cd /d "D:\work\ai_code\kemi-bt-board\scratch"
echo Running Bluetooth Scanner...
echo Output will be saved to bt_scan_result.txt
python bt_scan_v2.py > bt_scan_result.txt 2>&1
echo Done! Check bt_scan_result.txt

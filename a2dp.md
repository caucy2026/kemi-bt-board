1.设置动态切换蓝牙profile开关：setprop persist.sys_im.blutooth.is_a2dp_dynamic true
2. 把Bluetooth.apk 替换到 /system/app/Bluetooth,然后重启
3. 通过设置 settings put global bluetooth_disabled_profiles 去切换服务：
# 只开A2DP Sink，屏蔽Source
settings put global bluetooth_disabled_profiles 4

# 只开A2DP Source，屏蔽Sink
settings put global bluetooth_disabled_profiles 2048

# 两个全部关闭（最终需求）
settings put global bluetooth_disabled_profiles 205
# DO NOT ORDER / 禁止直接下单

这是 MOTO GPS Rev A 的工程审查包，用于确认真实铜线、钻孔、贴装坐标和结构密度，不是嘉立创投板放行包。ERC、DRC、未连接及原理图/PCB 一致性可以为零，但以下物理项尚未完成：

- 原屏 FPC 接触面、插入方向、厚度和 Pin 1 实物确认；
- USB-C 与外壳开口、PCB Z 高、插头包络确认；
- 所有自建封装第二人逐脚签核；
- 充电/升降压电源券和密封温升测试；
- GNSS/Wi-Fi 天线、阻抗叠层和整机 RF 测试；
- 电池、屏幕、按键、螺钉及佳明卡口整机 3D/实物干涉检查。

`review_gerbers_DO_NOT_ORDER/` 只供 CAM/DFM 预审。本目录故意不生成可直接上传的Gerber ZIP；正式文件只能由 `fab_release.py export` 在全部门槛关闭后生成。

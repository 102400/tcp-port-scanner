- 基于NIO的TCP端口扫描
- 例子见Main.java

1. scannerA提交地址到一个总的阻塞队列
2. connect工作线程从总的阻塞队列拿到地址注册并连接
3. select工作线程收到触发, 尝试结束连接, 能结束连接的说明端口存在, 把成功端口塞入到scannerA的阻塞队列里
4. scannerA拿到成功的地址
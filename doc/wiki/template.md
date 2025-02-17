## 消息模板配置


> 在0.6版本后，部分消息渠道支持markdown格式，详细使用方法请参考文档 [markdown格式消息](./template_markdown.md)


消息模板使用的语法是freemarker，具体用法参考freemarker官方文档。可用的变量分两部分。一部分是报警规则设置，一部分是查询出来的数据。不同规则可使用的变量如下表格。

 字段名  | 类型     | 说明  | 适用的判断类型
-------- |----------| ------- | -----
TIME_WINDOW | int | 查询时间范围窗口大小(单位: 分钟) | 数值
NUMBER | double | 数值类型值 | 数值
THRESHOLD | double | 判断阈值 | 数值
COUNT | long | 查询记录数量 | 数值
ALERT_SILENCE | long | 静默时间 | 数值
CURRENT_TIME | String | 当前时间 | 数值
ALARM_ID | long | 监控ID | 数值
ALARM_NAME | String | 监控名称 | 数值

当聚合类型是avg时，NUMBER表示平均值；当聚合类型是count时，NUMBER表示数量；当聚合类型是sum时，NUMBER表示和；以此类推。

### HTTP数据内置变量

 字段名  | 类型     | 说明  | 适用的数据源
-------- |----------| ------- | -----
HTTP_STATUS | int | HTTP状态码 | HTTP
HTTP_COST | long | 请求耗时 | HTTP

查询的数据可用变量取决于你的数据格式。以我们部门程序日志数值监控为例，可用变量和我们日志格式是一致的。如下表格:

序号 | 字段名  | 类型     | 说明     | Elasticsearch存储
----|-------- |----------| ------- | -----
1   | LogAt   | DateTime | 日志时间 | iso8601
2   | TraceId | string   | 跟踪Id   | 不分词
3   | Department | string | 部门    | 不分词,统一小写
4   | Team    | string   | 团队  | 不分词,统一小写
5   | Project | string   | 项目名称  | 不分词,统一小写
6   | Host | string   | 域名  | 不分词,统一小写
7   | ServerIP | string   | 服务器IP  | 不分词,统一小写
8   | ContextPath | string   | 虚拟目录  | 不分词,统一小写
9   | UriStem | string   | url主干  | 不分词,统一小写
10  | QueryString | string   | GET参数  | 分词
11  | FormString | string   | POST参数  | 分词
12  | UserAgent | string   | UserAgent  | 分词
13  | Level | string   | 日志级别  | 不分词,统一大写
14  | Class | string   | 记录日志所在类名  | 不分词
15  | Method | string   | 记录日志所在方法名  | 不分词
16  | MethodParams | string   | 抛出自定义异常方法参数  | 分词
17  | Line | int   | 行号  | 整数
18  | Logger | string   | 日志名  | 不分词
19  | IOType | string   | 自定义异常io类型  | 不分词
20  | ExceptionType | string   | 异常类型  | 不分词
21  | ExceptionMessage | string   | 异常信息  | 分词
22  | CustomMessage | string   | 自定义信息  | 分词
23  | StackTrace | string   | 堆栈信息  | 分词
24  | HawkKey  | string | Key  | 不分词

这样我们一般为程序日志报警定制的消息模板为:

```
${Project}最近${TIME_WINDOW}分钟内有异常日志${NUMBER}条。最近一条异常信息:
服务器IP: ${ServerIP}
异常类型: ${ExceptionType}
自定义信息: ${CustomMessage}
异常信息: ${ExceptionMessage}
```

比如监控访问日志，我们一般定制的消息模板为:

```
域名${host}最近${TIME_WINDOW}分钟内有非200请求${NUMBER}。最近一条信息:
服务器IP: ${server_ip}
调用IP: ${client_ip}
HttpCode: ${code}
耗时: ${cost}
```

或者我们监控访问日志的耗时，定制的消息模板为：

```
域名${host}最近${TIME_WINDOW}分钟内有耗时超过500ms请求${NUMBER}。最近一条信息:
服务器IP: ${server_ip}
调用IP: ${client_ip}
HttpCode: ${code}
耗时: ${cost}
```

这里用我们内部使用的例子供大家参考使用，具体模板内容，你需要自己根据数据格式定制。如果你想使用我们的日志格式，请参考
另外一个开源项目: autolog4j[https://github.com/AutohomeCorp/autolog4j]

## Q: 报警字段值太长，如何处理？

freemarker提供了字符串截取的操作，例如:

```
${userName?truncate(16)}
```

如果userName长度超过16，将被截取。更多使用方法请参考[freemarker文档](https://freemarker.apache.org/docs/ref_builtins_string.html#ref_builtin_truncate);

## Q: 如何设置报警消息头？

默认报警消息title是: 霜之哀伤监控平台。可以配置config_map表config_key为AlertTitle的行的config_value值来设置自定义报警消息头。例如：

```sql
INSERT INTO config_map(config_key, config_value, creator, create_at, modifier, modify_at)
VALUES('AlertTitle', '霜之哀伤监控平台', 'admin', now(), 'admin', now());
```

同一个config_key禁止配置多条，对config_key建了唯一索引。

注意如果你的报警机器人依赖报警消息头作为关键词，请记得同时改机器人配置，否则会导致机器人消息无法发送。

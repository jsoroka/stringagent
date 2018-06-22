# StringAgent

StringAgent monitors requests to a [Javalin](https://javalin.io) server and uses [javassist](https://www.javassist.org) 
to count the number of `java.lang.String` allocations that occur while a [`service`](
https://github.com/tipsy/javalin/blob/8dc1db566f9076762bccef91bc1dc874822f8669/src/main/java/io/javalin/core/JavalinServlet.kt#L40) 
method generates a response.  It also generates a unique ID for every request/response and measures the time it took to 
generate that response.  StringAgent reports these datapoints via HTTP headers, [slf4j](https://www.slf4j.org/) and, 
optionally, to a [statsd](https://github.com/etsy/statsd) endpoint.

## Purpose

I am applying for a job that involves maintenance of a javaagent and this sample project aims to show that I can help.

## Usage

`java -javaagent:/path/to/stringagent-agent-1.0-SNAPSHOT.jar[=statsd=hostname:port] ...`

### Notes/TODO

* Instrumenting `java.lang.String` generally requires getting your instrumentation classes onto the 
  bootclasspath.  However, then your instrumentation classes can't properly access anything off the 
  bootclasspath, like `javax.servlet.*` for an example.  One solution to try would be to have a jar 
  that contains only the code that needs to be on the bootclasspath, which is packaged into the agent 
  jar, and which it then writes out to something like `~/.stringagent/bootstrap.jar`, and then 
  [appends](https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html#appendToBootstrapClassLoaderSearch-java.util.jar.JarFile-) 
  that onto the bootclasspath.

* More tests are required:
  * run all tests with post-startup runtime attaching of agent
  * add mock statsd server to test stats reporting
  * add more frameworks than just Javalin
  * setup test harness for JDK 6,7,8,9,10

# BukkitWorker
Make it so it's possible to get the same behavior of asynchronous tasks with methods that can only be run synchronously.
## Correct Usage
We know that commands cannot be run asynchronously, and running about 10000 commands within a loop could possibly lag the server. Here is the fix using BukkitWorker:
### Run
```java
for (int i = 0; i < 10000; i++) {
    BukkitWorker.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say Hi"));
}
```
### Run New
```java
int id = BukkitWorker.runNew(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say Hi"));
for (int i = 0; i < 100000; i++) {
    BukkitWorker.runContinue(id, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say Hi"));
}
```
The difference between Run and Run New is that anything that is executed using the `.run(..)` method will wait for all the tasks that were also run using the same method to finish. However, `.runNew` and `.runContinue` will only wait for the tasks that were run using the same id. This applies to Workload Tasks too, each workload task will have a separate set of methods that will wait for each other.
### Workload Tasks
```java
WorkloadTask task = BukkitWorker.prepareTask();
for (int i = 0; i < 10000; i++) {
    task.addWorkload(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say Hi"));
}
task.start();
```
### Prepared Loops
What if we want to loop asynchronously through a list? You can do that too.
```java
BukkitWorker.prepareLoop(Arrays.asList("element1", "element2")).forEach(s -> {
    // Do async stuff with the element
}).whenCompleteDoAsync(() -> {
    // COMPLETED! Do stuff here.
});
// Any iterable can be used.
```
If we want to break a loop:
```java
PreparedLoop loop = BukkitWorker.prepareLoop(..);
loop.forEach(...);
// Breaks the loop
loop.forceBreak();
// Setup a break condition
loop.breakIf(s -> s == null);
```
## Incorrect Usage
An incorrect usage would be to put the loop directly in the run method like this:
```java
BukkitWorker.run(() -> {
  for (int i = 0; i < 10000; i++) {
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say Hi"));
  }
});
```
This will most likely eat your CPU.
### Other Methods
```java
BukkitWorker.prepareLoopInt(/* Optional */ int startIndex, int size, /* Optional */ IntCondition intCondition,
			/* Optional */ IntOperation intOperation); // Prepares async integer loop
BukkitWorker.cancel(id / work load task / work load runnable); // Cancels execution of tasks
BukkitWorker.isPendingTasks(id);
BukkitWorker.isCancelled(id);
workloadTask.cancel();
workloadTask.isCancelled();
// etc... Check the methods yourselves
```

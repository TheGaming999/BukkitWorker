package me.prisonranksx.utilities;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 
 * Access any bukkit feature as if it's run in an asynchronous task.
 * <p>
 * Basically, it mimicks bukkit's async tasks behavior in the main thread.
 * <p>
 * Made using <a href="https://www.spigotmc.org/threads/409003/">How to handle
 * heavy splittable tasks</a> by
 * <a href="https://www.spigotmc.org/members/43809/">7smile7</a>
 * </p>
 */
public class BukkitWorker {

	private static final WorkloadRunnable MAIN_WORKLOAD_RUNNABLE = new WorkloadRunnable();
	private static final Map<Integer, WorkloadRunnable> WORKLOADS = new HashMap<>();
	private static final JavaPlugin PLUGIN = JavaPlugin.getProvidingPlugin(BukkitWorker.class);

	static {
		MAIN_WORKLOAD_RUNNABLE.start();
	}

	private static interface Workload {

		/**
		 * 
		 * @return true if computation is need, false otherwise
		 */
		boolean compute();

	}

	public static class LoopFuture<T> {

		private CompletableFuture<T> completableFuture;
		private int currentElementIndex;
		private T currentElement;

		public LoopFuture() {
			completableFuture = new CompletableFuture<T>();
		}

		public static <T> LoopFuture<T> createCompleted() {
			LoopFuture<T> loopFuture = new LoopFuture<>();
			loopFuture.completableFuture.complete(null);
			return loopFuture;
		}

		public static void setLoopFuture(LoopFuture<?> loopFuture, LoopFuture<?> loopFuture2) {
			loopFuture = loopFuture2;
		}

		public static void forceComplete(LoopFuture<?> loopFuture) {
			loopFuture.forceComplete();
		}

		/**
		 * 
		 * @return Completes the loop future with a null object
		 */
		public LoopFuture<T> forceComplete() {
			completableFuture.complete(null);
			return this;
		}

		/**
		 * @param t object to pass for completion
		 * @return Completes the loop future with a specific object
		 */
		public LoopFuture<T> forceComplete(T t) {
			completableFuture.complete(t);
			return this;
		}

		public CompletableFuture<Void> whenCompleteDoAsync(Runnable runnable) {
			return completableFuture.thenRunAsync(runnable);
		}

		public CompletableFuture<Void> whenCompleteDoSync(Runnable runnable) {
			return completableFuture.thenRun(runnable);
		}

		public CompletableFuture<Void> whenCompleteAcceptAsync(Consumer<T> consumer) {
			return completableFuture.thenAcceptAsync(consumer);
		}

		public CompletableFuture<Void> whenCompleteAcceptSync(Consumer<T> consumer) {
			return completableFuture.thenAccept(consumer);
		}

		public CompletableFuture<T> getCompletableFuture() {
			return completableFuture;
		}

		public int getCurrentIndex() {
			return currentElementIndex;
		}

		public T getCurrentElement() {
			return currentElement;
		}

		@Override
		public String toString() {
			return currentElement + ":" + currentElementIndex + ":" + completableFuture.toString();
		}

	}

	public static abstract class AbstractPreparedLoop<T> {

		private Object object;

		/**
		 * Start a for loop
		 * 
		 * @param action action to perform on loop element
		 * @return LoopFuture
		 */
		public abstract LoopFuture<T> forEach(Consumer<? super T> action);

		/**
		 * Uses pseudo async alongside bukkit async, this means that non-thread safe
		 * methods should be handled manually.
		 * 
		 * @param action action to perform
		 * @return LoopFuture
		 */
		public abstract LoopFuture<T> asyncForEach(Consumer<? super T> action);

		/**
		 * Start a for loop
		 * 
		 * @param action          action to perform on loop element
		 * @param maxMilliseconds how many milliseconds a tick can last, increasing this
		 *                        value will speed up the task inreturn of decreasing
		 *                        the tps by a little
		 * @return LoopFuture
		 */
		public abstract LoopFuture<T> forEach(double maxMilliseconds, Consumer<? super T> action);

		/**
		 * Uses pseudo async alongside bukkit async, this means that non-thread safe
		 * methods should be handled manually.
		 * 
		 * @param action          action to perform
		 * @param maxMilliseconds how many milliseconds a tick can last, increasing this
		 *                        value will speed up the task inreturn of decreasing
		 *                        the tps by a little
		 * @return LoopFuture
		 */
		public abstract LoopFuture<T> asyncForEach(double maxMilliseconds, Consumer<? super T> action);

		public abstract void forceBreak();

		public BukkitTask sync(Runnable runnable) {
			return Bukkit.getScheduler().runTask(PLUGIN, runnable);
		}

		public BukkitTask async(Runnable runnable) {
			return Bukkit.getScheduler().runTaskAsynchronously(PLUGIN, runnable);
		}

		public void storeObject(Object object) {
			this.object = object;
		}

		public Object getObject() {
			return object;
		}

	}

	public static class PreparedLoop<T> extends AbstractPreparedLoop<T> {

		private Iterable<T> iterable;
		private LoopFuture<T> loopFuture;
		private Predicate<T> cancelationPredicate;
		private Predicate<T> continuationPredicate;

		public PreparedLoop(@Nonnull Iterable<T> iterable) {
			this.iterable = iterable;
			if (iterable == null)
				throw new NullPointerException("PreparedLoop creation failure: iterable or collection is null!");
			else
				loopFuture = new LoopFuture<T>();
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 * 
		 * @param preparedLoop prepared loop to break
		 */
		public static void forceBreak(PreparedLoop<?> preparedLoop) {
			preparedLoop.cancelationPredicate = o -> (o == null || o instanceof Object);
		}

		public PreparedLoop<T> breakIf(Predicate<T> predicate) {
			cancelationPredicate = predicate;
			return this;
		}

		public PreparedLoop<T> alsoBreakIf(Predicate<T> predicate) {
			cancelationPredicate.and(predicate);
			return this;
		}

		public PreparedLoop<T> continueIf(Predicate<T> predicate) {
			continuationPredicate = predicate;
			return this;
		}

		public PreparedLoop<T> alsoContinueIf(Predicate<T> predicate) {
			continuationPredicate.and(predicate);
			return this;
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 */
		@Override
		public void forceBreak() {
			cancelationPredicate = o -> (o == null || o instanceof Object);
		}

		@Override
		public LoopFuture<T> forEach(Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.start();
			for (T element : iterable) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public LoopFuture<T> forEach(double maxMilliseconds, Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.start();
			for (T element : iterable) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public String toString() {
			return iterable.toString() + " " + loopFuture.toString();
		}

		@Override
		public LoopFuture<T> asyncForEach(Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.startAsync();
			for (T element : iterable) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public LoopFuture<T> asyncForEach(double maxMilliseconds, Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.startAsync();
			for (T element : iterable) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

	}

	public static class ArrayPreparedLoop<T> extends AbstractPreparedLoop<T> {

		private T[] array;
		private LoopFuture<T> loopFuture;
		private Predicate<T> cancelationPredicate;
		private Predicate<T> continuationPredicate;

		public ArrayPreparedLoop(@Nonnull T[] array) {
			this.array = array;
			if (array == null)
				throw new NullPointerException("PreparedLoop creation failure: array is null!");
			else
				loopFuture = new LoopFuture<T>();
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 * 
		 * @param preparedLoop prepared loop to break
		 */
		public static void forceBreak(ArrayPreparedLoop<?> preparedLoop) {
			preparedLoop.cancelationPredicate = o -> (o == null || o instanceof Object);
		}

		public ArrayPreparedLoop<T> breakIf(Predicate<T> predicate) {
			cancelationPredicate = predicate;
			return this;
		}

		public ArrayPreparedLoop<T> alsoBreakIf(Predicate<T> predicate) {
			cancelationPredicate.and(predicate);
			return this;
		}

		public ArrayPreparedLoop<T> continueIf(Predicate<T> predicate) {
			continuationPredicate = predicate;
			return this;
		}

		public ArrayPreparedLoop<T> alsoContinueIf(Predicate<T> predicate) {
			continuationPredicate.and(predicate);
			return this;
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 */
		@Override
		public void forceBreak() {
			cancelationPredicate = o -> (o == null || o instanceof Object);
		}

		@Override
		public LoopFuture<T> forEach(Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.start();
			for (T element : array) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public LoopFuture<T> forEach(double maxMilliseconds, Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.start();
			for (T element : array) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public LoopFuture<T> asyncForEach(Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.startAsync();
			for (T element : array) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public LoopFuture<T> asyncForEach(double maxMilliseconds, Consumer<? super T> action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.startAsync();
			for (T element : array) {
				if (continuationPredicate != null) if (continuationPredicate.test(element)) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(element)) break;
				workloadRunnable.addWorkload(new ConsumerWorkload<T>(element, action));
				loopFuture.currentElement = element;
				loopFuture.currentElementIndex++;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		@Override
		public String toString() {
			return array.toString() + " " + loopFuture.toString();
		}

	}

	public static class IntPreparedLoop {

		private int size, currentIndex;
		private IntCondition intCondition;
		private IntOperation intOperation;
		private LoopFuture<Integer> loopFuture;
		private IntPredicate cancelationPredicate;
		private IntPredicate continuationPredicate;

		public IntPreparedLoop(int startIndex, IntCondition intCondition, IntOperation intOperation, int size) {
			this.size = size;
			this.intCondition = intCondition;
			this.intOperation = intOperation;
			this.intOperation.set(startIndex);
			loopFuture = new LoopFuture<Integer>();
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 * 
		 * @param intPreparedLoop prepared loop to break
		 */
		public static void forceBreak(IntPreparedLoop intPreparedLoop) {
			intPreparedLoop.cancelationPredicate = i -> (i <= Integer.MAX_VALUE);
		}

		public IntPreparedLoop breakIf(IntPredicate predicate) {
			cancelationPredicate = predicate;
			return this;
		}

		public IntPreparedLoop alsoBreakIf(IntPredicate predicate) {
			cancelationPredicate.and(predicate);
			return this;
		}

		public IntPreparedLoop continueIf(IntPredicate predicate) {
			continuationPredicate = predicate;
			return this;
		}

		public IntPreparedLoop andContinueIf(IntPredicate predicate) {
			continuationPredicate.and(predicate);
			return this;
		}

		public IntPreparedLoop setSize(int newSize) {
			size = newSize;
			return this;
		}

		/**
		 * Forcefully breaks a loop by setting a break condition that always returns
		 * true
		 */
		public void forceBreak() {
			cancelationPredicate = i -> (i <= Integer.MAX_VALUE);
		}

		public LoopFuture<Integer> forEach(IntConsumer action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.start();
			for (currentIndex = intOperation.get(); intCondition.isTrue(currentIndex,
					size); currentIndex = intOperation.update()) {
				if (continuationPredicate != null) if (continuationPredicate.test(intOperation.get())) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(intOperation.get())) break;
				int index = currentIndex;
				workloadRunnable.addWorkload(new IntWorkload(index, action));
				loopFuture.currentElement = index;
				loopFuture.currentElementIndex = index;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		public LoopFuture<Integer> forEach(double maxMilliseconds, IntConsumer action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.start();
			for (currentIndex = intOperation.get(); intCondition.isTrue(currentIndex,
					size); currentIndex = intOperation.update()) {
				if (continuationPredicate != null) if (continuationPredicate.test(intOperation.get())) continue;
				if (cancelationPredicate != null) if (cancelationPredicate.test(intOperation.get())) break;
				int index = currentIndex;
				workloadRunnable.addWorkload(new IntWorkload(index, action));
				loopFuture.currentElement = index;
				loopFuture.currentElementIndex = index;
			}
			workloadRunnable.run(() -> {
				loopFuture.completableFuture.complete(loopFuture.currentElement);
				workloadRunnable.bukkitTask.cancel();
				workloadRunnable.clearWorkloads();
			});
			return loopFuture;
		}

		/**
		 * Uses pseudo async alongside bukkit async, this means that non-thread safe
		 * methods should be handled manually.
		 * 
		 * @param action action to perform
		 * @return LoopFuture
		 */
		public LoopFuture<Integer> asyncForEach(IntConsumer action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable();
			workloadRunnable.startAsync();
			Bukkit.getScheduler().runTaskAsynchronously(PLUGIN, () -> {
				for (currentIndex = intOperation.get(); intCondition.isTrue(currentIndex,
						size); currentIndex = intOperation.update()) {
					if (continuationPredicate != null) if (continuationPredicate.test(intOperation.get())) continue;
					if (cancelationPredicate != null) if (cancelationPredicate.test(intOperation.get())) break;
					int index = currentIndex;
					workloadRunnable.addWorkload(new IntWorkload(index, action));
					loopFuture.currentElement = index;
					loopFuture.currentElementIndex = index;
				}
				workloadRunnable.run(() -> {
					loopFuture.completableFuture.complete(loopFuture.currentElement);
					workloadRunnable.bukkitTask.cancel();
					workloadRunnable.clearWorkloads();
				});
			});
			return loopFuture;
		}

		/**
		 * Uses pseudo async alongside bukkit async, this means that non-thread safe
		 * methods should be handled manually.
		 * 
		 * @param action          action to perform
		 * @param maxMilliseconds {@link BukkitWorker#prepareTask(double)}
		 * @return LoopFuture
		 */
		public LoopFuture<Integer> asyncForEach(double maxMilliseconds, IntConsumer action) {
			WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
			workloadRunnable.startAsync();
			Bukkit.getScheduler().runTaskAsynchronously(PLUGIN, () -> {
				for (currentIndex = intOperation.get(); intCondition.isTrue(currentIndex,
						size); currentIndex = intOperation.update()) {
					if (continuationPredicate != null) if (continuationPredicate.test(intOperation.get())) continue;
					if (cancelationPredicate != null) if (cancelationPredicate.test(intOperation.get())) break;
					int index = currentIndex;
					workloadRunnable.addWorkload(new IntWorkload(index, action));
					loopFuture.currentElement = index;
					loopFuture.currentElementIndex = index;
				}
				workloadRunnable.run(() -> {
					loopFuture.completableFuture.complete(loopFuture.currentElement);
					workloadRunnable.bukkitTask.cancel();
					workloadRunnable.clearWorkloads();
				});
			});
			return loopFuture;
		}

	}

	public interface IntCondition {

		/**
		 * This equals i < size in a for loop
		 * 
		 * @return A condition of {@code i < size} the most used condition in for loops
		 */
		public static IntCondition lessThanInt() {
			return new LessThanIntCondition();
		}

		/**
		 * This equals i > size in a for loop
		 * 
		 * @return A condition of {@code i > size} the second most used condition in for
		 *         loops
		 */
		public static IntCondition moreThanInt() {
			return new MoreThanIntCondition();
		}

		/**
		 * This equals i != size in a for loop
		 * 
		 * @return A condition of {@code i != size} as if it's used in a for loop
		 */
		public static IntCondition notEqualInt() {
			return new NotEqualIntCondition();
		}

		/**
		 * This equals i == size in a for loop
		 * 
		 * @return A condition of {@code i == size} as if it's used in a for loop
		 */
		public static IntCondition equalInt() {
			return new EqualIntCondition();
		}

		boolean isTrue(int currentIndex, int size);

	}

	private static class LessThanIntCondition implements IntCondition {

		@Override
		public boolean isTrue(int currentIndex, int size) {
			return currentIndex < size;
		}

	}

	private static class MoreThanIntCondition implements IntCondition {

		@Override
		public boolean isTrue(int currentIndex, int size) {
			return currentIndex > size;
		}

	}

	private static class NotEqualIntCondition implements IntCondition {

		@Override
		public boolean isTrue(int currentIndex, int size) {
			return currentIndex != size;
		}

	}

	private static class EqualIntCondition implements IntCondition {

		@Override
		public boolean isTrue(int currentIndex, int size) {
			return currentIndex == size;
		}

	}

	public interface IntOperation {

		/**
		 * This equals i++ used in a for loop
		 * 
		 * @return Operation: i++
		 */
		public static IntOperation increaseInt() {
			return new IncreaseIntOperation();
		}

		/**
		 * This equals i+(i) used in a for loop
		 * 
		 * @param i increase by (1 by default)
		 * @return Operation: i+(i)
		 */
		public static IntOperation increaseInt(int i) {
			IncreaseIntOperation operation = new IncreaseIntOperation();
			operation.by = i;
			return operation;
		}

		/**
		 * This equals i-- used in a for loop
		 * 
		 * @return Operation: i--
		 */
		public static IntOperation decreaseInt() {
			return new DecreaseIntOperation();
		}

		/**
		 * This equals i-(i) used in a for loop
		 * 
		 * @param i decrease by (1 by default)
		 * @return Operation: i-(i)
		 */
		public static IntOperation decreaseInt(int i) {
			DecreaseIntOperation operation = new DecreaseIntOperation();
			operation.by = i;
			return operation;
		}

		int get();

		int set(int i);

		int update();

	}

	private static class IncreaseIntOperation implements IntOperation {

		int i = 0;
		int by = 1;

		public IncreaseIntOperation() {
			i = 0;
		}

		@Override
		public int update() {
			return i += by;
		}

		@Override
		public int get() {
			return i;
		}

		@Override
		public int set(int i) {
			return this.i = i;
		}

	}

	private static class DecreaseIntOperation implements IntOperation {

		int i = 0;
		int by = 1;

		public DecreaseIntOperation() {
			i = 0;
		}

		@Override
		public int update() {
			return i -= by;
		}

		@Override
		public int get() {
			return i;
		}

		@Override
		public int set(int i) {
			return this.i = i;
		}

	}

	/**
	 * Runs the specified {@code runnable} in the main workload, which means that
	 * any action that will be run, will wait for the previous actions that are also
	 * run in the main workload to finish before execution.
	 * 
	 * @param runnable code to run () ->
	 */
	public static void run(Runnable runnable) {
		MAIN_WORKLOAD_RUNNABLE.addWorkload(new RunnableWorkload(runnable));
	}

	/**
	 * Consumes the specified {@code consumer} in the main workload, which means
	 * that
	 * any consumer that will be consumed, will wait for the previous actions that
	 * are also
	 * run in the main workload to finish before execution.
	 * 
	 * @param consumer consumer to consume c ->
	 * @param object   object to include
	 */
	public static <T> void consume(Consumer<? super T> consumer, T object) {
		MAIN_WORKLOAD_RUNNABLE.addWorkload(new ConsumerWorkload<T>(object, consumer));
	}

	/**
	 * Supplies the specified {@code supplier} in the main workload, which means
	 * that
	 * any object that will be received from the supplier, will wait for the
	 * previous actions and suppliers that are also
	 * run and retrieved in the main workload to finish before execution.
	 * 
	 * @param supplier object to supply () -> object
	 */
	public static <T> SupplierWorkload<T> supply(Supplier<T> supplier) {
		return MAIN_WORKLOAD_RUNNABLE.addSupplierWorkload(new SupplierWorkload<T>(supplier));
	}

	/**
	 * Runs the specified {@code runnable} in a new workload, this means it won't
	 * wait for an action to finish unless it is run in the same workload using
	 * {@linkplain #runContinue(int, Runnable)}. This shouldn't be spammed, but
	 * rather should be used when starting a heavy task. After that, the task should
	 * be continued using runContinue
	 * 
	 * @param runnable code to run () ->
	 * @return workload id, for further usage in runContinue
	 */
	public static int runNew(Runnable runnable) {
		WorkloadRunnable workloadRunnable = new WorkloadRunnable();
		int id = WORKLOADS.size();
		workloadRunnable.addWorkload(new RunnableWorkload(runnable));
		workloadRunnable.start();
		WORKLOADS.put(id, workloadRunnable);
		return id;
	}

	/**
	 * Runs the specified {@code runnable} in a new workload, this means it won't
	 * wait for an action to finish unless it is run in the same workload using
	 * {@linkplain #runContinue(int, Runnable)}. This shouldn't be spammed, but
	 * rather should be used when starting a heavy task. After that, the task should
	 * be continued using runContinue
	 * 
	 * @param runnable        code to run () ->
	 * @param maxMilliseconds how long can a tick last by default it's set to 2.5.
	 *                        The value must be between 1.0 and 50.0 for a
	 *                        beneficial effect. The higher the faster the more
	 *                        server resources consumed.
	 * @return workload id, for further usage in runContinue
	 */
	public static int runNew(double maxMilliseconds, Runnable runnable) {
		WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
		int id = WORKLOADS.size();
		workloadRunnable.addWorkload(new RunnableWorkload(runnable));
		workloadRunnable.start();
		WORKLOADS.put(id, workloadRunnable);
		return id;
	}

	/**
	 * Runs the specified {@code runnable} in a new workload, this means it won't
	 * wait for an action to finish unless it is run in the same workload using
	 * {@linkplain #runContinue(int, Runnable)}. This shouldn't be spammed, but
	 * rather should be used when starting a heavy task. After that, the task should
	 * be continued using runContinue
	 * 
	 * @param runnable        code to run () ->
	 * @param maxMilliseconds how long can a tick last by default it's set to 2.5.
	 *                        The value must be between 1.0 and 50.0 for a
	 *                        beneficial effect. The higher the faster the more
	 *                        server resources consumed.
	 * @param id              a reference for the workload, so it can be accessed
	 *                        again
	 * @return the given workload id, for further usage in runContinue
	 */
	public static int runNew(int id, double maxMilliseconds, Runnable runnable) {
		WorkloadRunnable workloadRunnable = new WorkloadRunnable(maxMilliseconds);
		workloadRunnable.addWorkload(new RunnableWorkload(runnable));
		workloadRunnable.start();
		WORKLOADS.put(id, workloadRunnable);
		return id;
	}

	/**
	 * Continue using a workload by adding a task to it
	 * 
	 * @param id       id of a workload
	 * @param runnable code to run () ->
	 * @return specified workload id after workload addition
	 */
	public static int runContinue(int id, Runnable runnable) {
		WORKLOADS.get(id).addWorkload(new RunnableWorkload(runnable));
		return id;
	}

	/**
	 * Creates a workload task without starting it and without workloads.
	 * 
	 * @return workload task that manages workloads
	 */
	public static WorkloadTask prepareTask() {
		return new WorkloadTask(new WorkloadRunnable());
	}

	/**
	 * Creates a workload task without starting it and without workloads. In
	 * addition, it sets the maximum milliseconds a tick can last. It's set to 2.5
	 * by default, which is about 5% of a tick since a tick is 50 milliseconds.
	 * 
	 * @param maxMillisecondsPerTick how many milliseconds a tick can last,
	 *                               increasing this value will speed up the task in
	 *                               return of decreasing the tps by a little
	 * @return workload task that manages workloads
	 */
	public static WorkloadTask prepareTask(double maxMillisecondsPerTick) {
		return new WorkloadTask(new WorkloadRunnable(maxMillisecondsPerTick));
	}

	/**
	 * Loops through a collection's elements asynchronously
	 * 
	 * @param <T>        type of object
	 * @param collection collection to loop through
	 * @param action     action perform on loop elements
	 * @return LoopFuture that contains a CompletableFuture and the elements
	 *         currently being processed, returns a completed LoopFuture if
	 *         collection doesn't have elements
	 */
	public static <T> LoopFuture<T> forEach(Collection<T> collection, Consumer<? super T> action) {
		if (collection.isEmpty()) return LoopFuture.createCompleted();
		LoopFuture<T> loopFuture = new LoopFuture<>();
		WorkloadRunnable workloadRunnable = new WorkloadRunnable();
		workloadRunnable.start();
		for (T element : collection) {
			workloadRunnable.addWorkload(new RunnableWorkload(() -> action.accept(element)));
			loopFuture.currentElement = element;
			loopFuture.currentElementIndex++;
		}
		workloadRunnable.run(() -> loopFuture.completableFuture.complete(loopFuture.currentElement));
		return loopFuture;
	}

	/**
	 * 
	 * @param <T>  type of list's elements
	 * @param list list elements to prepare a loop for
	 * @return A PreparedLoop that can setup conditions before a loop starts
	 */
	public static <T> PreparedLoop<T> prepareLoop(List<T> list) {
		return new PreparedLoop<T>(list);
	}

	/**
	 * 
	 * @param <T>        type of collection's elements
	 * @param collection collection to prepare a loop for
	 * @return A PreparedLoop that can setup conditions before a loop starts
	 */
	public static <T> PreparedLoop<T> prepareLoop(Collection<T> collection) {
		return new PreparedLoop<T>(collection);
	}

	/**
	 * 
	 * @param <T>      type of iterable's elements
	 * @param iterable iterable to prepare a loop for
	 * @return A PreparedLoop that can setup conditions before a loop starts
	 */
	public static <T> PreparedLoop<T> prepareLoop(Iterable<T> iterable) {
		return new PreparedLoop<T>(iterable);
	}

	/**
	 * 
	 * @param <T>   type of array's elements
	 * @param array array elements to prepare a loop for
	 * @return An ArrayPreparedLoop that can setup conditions before a loop starts
	 */
	public static <T> ArrayPreparedLoop<T> prepareLoop(T[] array) {
		return new ArrayPreparedLoop<T>(array);
	}

	/**
	 * This equals:
	 * <p>
	 * {@code for (int i = 0; i < size; i++);}
	 * 
	 * @param size the number the loop ends at
	 * @return An equivalent to PreparedLoop, but for integers.
	 */
	public static IntPreparedLoop prepareLoopInt(int size) {
		return new IntPreparedLoop(0, IntCondition.lessThanInt(), IntOperation.increaseInt(), size);
	}

	/**
	 * This equals:
	 * <p>
	 * {@code for (int i = startIndex; i < size; i++);}
	 * 
	 * @param size       the number the loop ends at
	 * @param startIndex the number the loop starts from
	 * @return An equivalent to PreparedLoop, but for integers.
	 */
	public static IntPreparedLoop prepareLoopInt(int startIndex, int size) {
		return new IntPreparedLoop(startIndex, IntCondition.lessThanInt(), IntOperation.increaseInt(), size);
	}

	/**
	 * This equals:
	 * <p>
	 * {@code for (int i = 0; intCondition; intOperation);}
	 * 
	 * @param size         the number the loop ends at. It's used by the int
	 *                     condition
	 * @param intCondition the condition that keeps the loop running
	 * @param intOperation the operation that's performed on {@code i}, such as
	 *                     {@code IntOperation.increaseInt()} which equals
	 *                     {@code i++}
	 * @return An equivalent to PreparedLoop, but for integers.
	 */
	public static IntPreparedLoop prepareLoopInt(int size, IntCondition intCondition, IntOperation intOperation) {
		return new IntPreparedLoop(0, intCondition, intOperation, size);
	}

	/**
	 * This equals:
	 * <p>
	 * {@code for (int i = startIndex; intCondition; intOperation);}
	 * 
	 * @param size         the number the loop ends at. It's used by the int
	 *                     condition
	 * @param startIndex   the number the loop starts from
	 * @param intCondition the condition that keeps the loop running
	 * @param intOperation the operation that's performed on {@code i}, such as
	 *                     {@code IntOperation.increaseInt()} which equals
	 *                     {@code i++}
	 * @return An equivalent to PreparedLoop, but for integers.
	 */
	public static IntPreparedLoop prepareLoopInt(int startIndex, int size, IntCondition intCondition,
			IntOperation intOperation) {
		return new IntPreparedLoop(startIndex, intCondition, intOperation, size);
	}

	/**
	 * Cancels a workload by removing any pending tasks and canceling the bukkit
	 * task that holds it
	 * 
	 * @param id id of workload to cancel
	 */
	public static void cancel(int id) {
		if (!WORKLOADS.containsKey(id)) return;
		WORKLOADS.get(id).clearWorkloads();
		WORKLOADS.get(id).bukkitTask.cancel();
	}

	public static void cancel(WorkloadTask workloadTask) {
		if (workloadTask != null) workloadTask.cancel();
	}

	public static void cancel(WorkloadTask workloadTask, boolean clearWorkloads) {
		if (clearWorkloads) workloadTask.clearAll();
		workloadTask.cancel();
	}

	public static void cancel(WorkloadRunnable workloadRunnable) {
		workloadRunnable.bukkitTask.cancel();
	}

	public static void cancel(WorkloadRunnable workloadRunnable, boolean clearWorkloads) {
		if (clearWorkloads) workloadRunnable.clearWorkloads();
		workloadRunnable.bukkitTask.cancel();
	}

	public static void addCanceller(int id) {
		WORKLOADS.get(id).addWorkload(new CancelWorkload());
	}

	public static void addCanceller(WorkloadTask workloadTask) {
		workloadTask.addCancellerWorkload();
	}

	public static void addCanceller(WorkloadRunnable workloadRunnable) {
		workloadRunnable.addWorkload(new CancelWorkload());
	}

	/**
	 * Cancels a workload by removing any pending tasks and canceling the bukkit
	 * task that holds it, and then removes the work load from the memory if release
	 * is true.
	 * 
	 * @param id      id of workload to cancel
	 * @param release whether to remove workload from memory or not
	 */
	public static void cancel(int id, boolean release) {
		cancel(id);
		if (release) WORKLOADS.remove(id);
	}

	public static boolean isCancelled(int id) {
		return !WORKLOADS.containsKey(id) || WORKLOADS.get(id).workloadDeque.isEmpty();
	}

	public static boolean isReleased(int id) {
		return !WORKLOADS.containsKey(id);
	}

	public static boolean isPendingTasks(int id) {
		return !WORKLOADS.get(id).workloadDeque.isEmpty();
	}

	public static class WorkloadTask {

		private WorkloadRunnable workloadRunnable;
		private boolean cancelled;

		public WorkloadTask(WorkloadRunnable workloadRunnable) {
			this.workloadRunnable = workloadRunnable;
		}

		public WorkloadTask start() {
			workloadRunnable.start();
			return this;
		}

		public WorkloadTask start(boolean bukkitAsync) {
			if (bukkitAsync)
				workloadRunnable.startAsync();
			else
				workloadRunnable.start();
			return this;
		}

		public WorkloadTask addWorkload(Runnable runnable) {
			workloadRunnable.addWorkload(new RunnableWorkload(runnable));
			return this;
		}

		public <T> WorkloadTask addWorkload(T t, Consumer<T> consumer) {
			workloadRunnable.addWorkload(new ConsumerWorkload<T>(t, consumer));
			return this;
		}

		public WorkloadTask addWorkload(int i, IntConsumer intConsumer) {
			workloadRunnable.addWorkload(new IntWorkload(i, intConsumer));
			return this;
		}

		public <T> WorkloadTask addWorkload(Supplier<T> supplier) {
			workloadRunnable.addWorkload(new SupplierWorkload<T>(supplier));
			return this;
		}

		public WorkloadTask addCancellerWorkload() {
			workloadRunnable.addWorkload(new CancelWorkload());
			return this;
		}

		public void cancel() {
			workloadRunnable.bukkitTask.cancel();
			cancelled = true;
		}

		public boolean isCancelled() {
			return cancelled;
		}

		/**
		 * Removes all added workloads
		 */
		public void clearAll() {
			workloadRunnable.clearWorkloads();
		}

		public WorkloadRunnable getManagedRunnable() {
			return workloadRunnable;
		}

		public boolean hasWorkloads() {
			return !workloadRunnable.workloadDeque.isEmpty();
		}

	}

	public static class WorkloadRunnable implements Runnable {

		private static final double MAX_MILLIS_PER_TICK = 2.5;

		private int maxNanosPerTick = (int) (MAX_MILLIS_PER_TICK * 1E6);

		private final Deque<Workload> workloadDeque;

		private BukkitTask bukkitTask;

		public WorkloadRunnable() {
			workloadDeque = new ArrayDeque<>();
		}

		public WorkloadRunnable(double maxMillisecondsPerTick) {
			workloadDeque = new ArrayDeque<>();
			maxNanosPerTick = (int) (maxMillisecondsPerTick * 1E6);
		}

		public void addWorkload(Workload workload) {
			this.workloadDeque.add(workload);
		}

		public <T> SupplierWorkload<T> addSupplierWorkload(SupplierWorkload<T> workload) {
			this.workloadDeque.add(workload);
			return workload;
		}

		public void start() {
			bukkitTask = Bukkit.getScheduler().runTaskTimer(PLUGIN, this, 1, 1);
		}

		public void startAsync() {
			bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(PLUGIN, this, 1, 1);
		}

		public void run(Runnable runnable) {
			addWorkload(new RunnableWorkload(runnable));
		}

		public void clearWorkloads() {
			workloadDeque.clear();
		}

		@Override
		public void run() {
			long stopTime = System.nanoTime() + maxNanosPerTick;

			Workload nextLoad;

			while (System.nanoTime() <= stopTime && (nextLoad = this.workloadDeque.poll()) != null) {
				if (!nextLoad.compute()) bukkitTask.cancel();
			}
		}

	}

	private static class CancelWorkload implements Workload {

		@Override
		public boolean compute() {
			return false;
		}

	}

	private static class RunnableWorkload implements Workload {

		private Runnable runnable;

		public RunnableWorkload(Runnable runnable) {
			this.runnable = runnable;
		}

		@Override
		public boolean compute() {
			runnable.run();
			return true;
		}

	}

	private static class IntWorkload implements Workload {

		private int i;
		private IntConsumer consumer;

		public IntWorkload(int i, IntConsumer consumer) {
			this.i = i;
			this.consumer = consumer;
		}

		@Override
		public boolean compute() {
			consumer.accept(i);
			return true;
		}

	}

	private static class ConsumerWorkload<T> implements Workload {

		private T t;
		private Consumer<? super T> consumer;

		public ConsumerWorkload(T t, Consumer<? super T> consumer) {
			this.t = t;
			this.consumer = consumer;
		}

		@Override
		public boolean compute() {
			consumer.accept(t);
			return true;
		}

	}

	public static class SupplierWorkload<T> implements Workload {

		private Supplier<T> supplier;
		private CompletableFuture<T> future;
		private T t;

		public SupplierWorkload(Supplier<T> supplier) {
			this.supplier = supplier;
			this.future = new CompletableFuture<T>();
		}

		public T join() {
			return future.join();
		}

		@Override
		public boolean compute() {
			t = supplier.get();
			future.complete(t);
			return true;
		}

	}

}

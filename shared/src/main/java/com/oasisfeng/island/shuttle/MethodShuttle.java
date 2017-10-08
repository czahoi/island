package com.oasisfeng.island.shuttle;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.oasisfeng.android.service.AidlService;
import com.oasisfeng.android.service.Services;
import com.oasisfeng.android.util.Consumer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * Shuttle for general method invocation.
 *
 * Created by Oasis on 2017/3/31.
 */
public class MethodShuttle {

	public interface GeneralVoidMethod { void invoke(); }
	public interface GeneralMethod<ReturnType> { ReturnType invoke(); }

	/** @param lambda should be a lambda function without return value.
	 *                Context (but not its derivation) and types acceptable by {@link Parcel#writeValue(Object)} can be carried. */
	public static ListenableFuture<Void> runInProfile(final Context context, final GeneralVoidMethod lambda) {
		return shuttle(context, lambda);
	}

	/** @param lambda should be a lambda function without return value.
	 *                Context (but not its derivation) and types acceptable by {@link Parcel#writeValue(Object)} can be carried.
	 * @deprecated */
	public static <R> void runInProfile(final Context context, final GeneralMethod<R> lambda, final @Nullable Consumer<R> consumer) {
		final ListenableFuture<R> future = shuttle(context, lambda);
		if (consumer != null) future.addListener(() -> {
			final R result;
			try {
				result = future.get();
			} catch (final ExecutionException e) {
				Log.w("Shuttle", "Error executing " + lambda, e.getCause());
				return;
			} catch (final InterruptedException e) { return; }
			consumer.accept(result);
		}, MoreExecutors.directExecutor());
	}

	private static <Result> ListenableFuture<Result> shuttle(final Context context, final Object lambda) {
		final Class<?> clazz = lambda.getClass();
		final Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		if (constructors == null || constructors.length < 1) throw new IllegalArgumentException("The method must have at least one constructor");
		final Constructor<?> constructor = constructors[0];		// Extra constructor may be generated by "Instant Run" of Android Studio.
		final Class<?>[] constructor_params = constructor.getParameterTypes();

		final Field[] fields = clazz.getDeclaredFields();
		if (fields.length != constructor_params.length)
			throw new IllegalArgumentException("Parameter types mismatch: " + constructor + " / " + Arrays.deepToString(fields));

		final Object[] args = new Object[fields.length];
		for (int i = 0; i < fields.length; i++) try {
			final Field field = fields[i];
			if (field.getType() != constructor_params[i])
				throw new IllegalArgumentException("Parameter types mismatch: " + constructor + " / " + Arrays.deepToString(fields));
			field.setAccessible(true);
			final Object arg = field.get(lambda);
			if (field.getType() != Context.class) args[i] = arg;		// Context argument is intentionally left blank.
		} catch (final Exception e) {
			throw new IllegalArgumentException("Error enumerating lambda parameters.", e);
		}

		final MethodInvocation<Result> invocation = new MethodInvocation<>();
		invocation.clazz = clazz.getName();
		invocation.args = args;
		final SettableFuture<Result> future = SettableFuture.create();
		if (! Services.use(new ShuttleContext(context), IMethodShuttle.class, IMethodShuttle.Stub::asInterface, shuttle -> {
			try {
				shuttle.invoke(invocation);
			} catch (final Exception e) {
				future.setException(e);
			}
			if (invocation.throwable != null) future.setException(invocation.throwable);
			else future.set(invocation.result);
		})) future.setException(new IllegalStateException("Error connecting " + Service.class.getCanonicalName()));
		return future;
	}

	private MethodShuttle() {}

	@SuppressLint("Registered")		// Actually declared in the AndroidManifest.xml of module "engine".
	public static class Service extends AidlService<IMethodShuttle.Stub> {

		@Nullable @Override protected IMethodShuttle.Stub createBinder() {
			return new IMethodShuttle.Stub() {
				@Override public void invoke(final MethodInvocation invocation) throws RemoteException {
					try {
						final Class<?> clazz = Class.forName(invocation.clazz);
						final Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
						constructor.setAccessible(true);
						final Object[] args = invocation.args;
						final Class<?>[] arg_types = constructor.getParameterTypes();
						for (int i = 0; i < arg_types.length; i++) if (arg_types[i] == Context.class) args[i] = Service.this;	// Fill in context
						final Object instance = constructor.newInstance(args);
						if (instance instanceof GeneralVoidMethod)
							((GeneralVoidMethod) instance).invoke();
						else if (instance instanceof GeneralMethod) //noinspection unchecked
							invocation.result = ((GeneralMethod) instance).invoke();
						else throw new IllegalArgumentException("Internal error: method mismatch");
					} catch (Throwable t) {
						if (t instanceof InvocationTargetException) t = ((InvocationTargetException) t).getTargetException();
						invocation.throwable = t;
					}
				}
			};
		}
	}
}

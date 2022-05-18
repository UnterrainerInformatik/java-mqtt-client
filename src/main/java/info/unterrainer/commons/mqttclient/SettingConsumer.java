package info.unterrainer.commons.mqttclient;

import java.util.Objects;

@FunctionalInterface
public interface SettingConsumer<A, C> {

	void accept(A a, Object o, C c);

	default SettingConsumer<A, C> andThen(final SettingConsumer<? super A, ? super C> after) {
		Objects.requireNonNull(after);

		return (a, o, c) -> {
			accept(a, o, c);
			after.accept(a, o, c);
		};
	}
}

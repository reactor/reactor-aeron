/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.aeron.publisher;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.aeron.Context;
import reactor.aeron.subscriber.AeronSubscriber;
import reactor.aeron.utils.AeronTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.subscriber.AssertSubscriber;
import reactor.test.util.FlowSerializerUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * @author Anatoly Kadyshev
 */
public class AeronSubscriberPublisherUnicastTest extends CommonSubscriberPublisherTest {

	@Override
	protected Context createContext(String name) {
		return Context.create().name(name)
				.senderChannel(senderChannel)
				.receiverChannel(AeronTestUtils.availableLocalhostChannel());
	}

	@Test
	public void testNextSignalIsReceivedByTwoPublishers() throws InterruptedException {
		AeronSubscriber aeronSubscriber = AeronSubscriber.create(createContext("subscriber"));

		AeronTestUtils.newByteBufferFlux("1","2","3","4","5","6").subscribe(aeronSubscriber);

		AeronFlux publisher1 = new AeronFlux(createContext("publisher1"));

		AssertSubscriber<String> client1 = AssertSubscriber.create(0);
		AeronTestUtils.bufferToString(publisher1).subscribe(client1);


		client1.request(3);

		client1.awaitAndAssertNextValues("1", "2", "3");

//		System.out.println(FlowSerializerUtils.scan(aeronSubscriber).toString());
//		System.out.println(FlowSerializerUtils.scan(client1).toString());

		AeronFlux publisher2 = new AeronFlux(createContext("publisher2")
				.receiverChannel(AeronTestUtils.availableLocalhostChannel()));

		AssertSubscriber<String> client2 = AssertSubscriber.create(0);
		AeronTestUtils.bufferToString(publisher2).subscribe(client2);


		client2.request(6);

		client2.awaitAndAssertNextValues("1", "2", "3", "4", "5", "6");

		//TODO: This call should not be required
		client2.request(1);
		client2.await(TIMEOUT).assertComplete();

//		System.out.println(FlowSerializerUtils.scan(aeronSubscriber).toString());
//		System.out.println(FlowSerializerUtils.scan(client2).toString());
//		System.out.println(FlowSerializerUtils.scan(client1).toString());

		client1.request(3);

		client1.awaitAndAssertNextValues("4", "5", "6");

		//TODO: This call should not be required
		client1.request(1);
		client1.await(TIMEOUT).assertComplete();

//		System.out.println(FlowSerializerUtils.scan(aeronSubscriber).toString());
	}

	@Test
	public void testSubscriptionCancellationTerminatesAeronFlux() throws InterruptedException {
		AeronSubscriber aeronSubscriber = AeronSubscriber.create(createContext("subscriber").autoCancel(true));

		AeronTestUtils.newByteBufferFlux("1","2","3","4","5","6").subscribe(aeronSubscriber);

		AeronFlux publisher = new AeronFlux(createContext("publisher").autoCancel(false));
		AssertSubscriber<String> client = AssertSubscriber.create(0);

		AeronTestUtils.bufferToString(publisher).subscribe(client);

		client.request(3);
		client.awaitAndAssertNextValues("1", "2", "3");

		client.cancel();

		AssertSubscriber.await(TIMEOUT, "publisher wasn't terminated", publisher::isTerminated);
	}

	private static class HangingOnCompleteSubscriber implements Subscriber<String> {

		CountDownLatch completeReceivedLatch = new CountDownLatch(1);

		CountDownLatch canReturnLatch = new CountDownLatch(1);

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Integer.MAX_VALUE);
		}

		@Override
		public void onNext(String s) {
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onComplete() {
			try {
				completeReceivedLatch.countDown();
				canReturnLatch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	public void testPublisherCanConnectToATerminalButRunningSubscriber() throws InterruptedException {
		AeronSubscriber aeronSubscriber = AeronSubscriber.create(createContext("subscriber"));
		AeronTestUtils.newByteBufferFlux("1","2","3").subscribe(aeronSubscriber);

		AeronFlux flux1 = new AeronFlux(createContext("publisher"));
		HangingOnCompleteSubscriber client = new HangingOnCompleteSubscriber();
		AeronTestUtils.bufferToString(flux1).subscribe(client);

		assertTrue(client.completeReceivedLatch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS));

		AeronFlux flux2 = new AeronFlux(createContext("publisher2"));
		AssertSubscriber<String> client2 = AssertSubscriber.create(0);
		AeronTestUtils.bufferToString(flux2).subscribe(client2);

		client2.request(3);

		client2.awaitAndAssertNextValueCount(3);

		//TODO: This should not be required
		client2.request(1);

		client2.await(TIMEOUT).assertComplete();

		client.canReturnLatch.countDown();
	}

	@Test
	public void testRequestAfterCompleteEventIsNoOp() {
		AeronSubscriber aeronSubscriber = AeronSubscriber.create(createContext("subscriber"));
		AeronTestUtils.newByteBufferFlux("1","2","3").subscribe(aeronSubscriber);

		AeronFlux publisher = new AeronFlux(createContext("publisher").autoCancel(false));
		AssertSubscriber<String> client = AssertSubscriber.create(0);

		AeronTestUtils.bufferToString(publisher).subscribe(client);

		client.request(3);

		client.awaitAndAssertNextValues("1", "2", "3");

		//TODO: Should not be required
		client.request(1);

		client.await(TIMEOUT).assertComplete();

		AssertSubscriber.await(TIMEOUT, () -> "Publisher hasn't been terminated", publisher::isTerminated);

		client.request(1);
	}

	@Test
	public void testRequestAfterErrorEventIsNoOp() throws InterruptedException {
		AeronSubscriber aeronSubscriber = AeronSubscriber.create(createContext("subscriber"));

		Flux.<ByteBuffer>error(new RuntimeException("Oops!")).subscribe(aeronSubscriber);

		AeronFlux publisher = new AeronFlux(createContext("publisher").autoCancel(false));
		AssertSubscriber<String> client = AssertSubscriber.create(0);

		AeronTestUtils.bufferToString(publisher).subscribe(client);

		client.request(1);

		client.await(TIMEOUT).assertError();

		AssertSubscriber.await(TIMEOUT, () -> "Publisher hasn't been terminated", publisher::isTerminated);

		client.request(1);
	}

}
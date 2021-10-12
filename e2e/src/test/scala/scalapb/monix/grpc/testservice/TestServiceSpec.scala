package scalapb.monix.grpc.testservice

import munit.Location
import io.grpc.{Metadata, Server, StatusRuntimeException}
import monix.eval.Task
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{Duration, DurationInt}
import monix.reactive.subjects.PublishToOneSubject
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import monix.execution.CancelablePromise
import cats.effect.ExitCase
import io.grpc.Status

class TestServiceSpec extends GrpcBaseSpec {
  override def munitTimeout: Duration = 6.seconds

  testGrpc("unary call responds successfully") { state =>
    state.stub
      .unary(Request(Request.Scenario.OK))
      .map(r => assertEquals(r.out, 1))
  }

  testGrpc("unary call fails with a server error") { state =>
    state.stub
      .unary(Request(Request.Scenario.ERROR_NOW))
      .expectServerSilentException
  }

  testGrpc("unary call times out") { state =>
    state.stub
      .unary(Request(Request.Scenario.DELAY))
      .timeout(1.seconds)
      .expectTimeoutException
  }

  testGrpc("server streaming call responds successfully") { state =>
    state.stub
      .serverStreaming(Request(Request.Scenario.OK))
      .toListL
      .expectDiff(
        """
          |Response(1,0,Vector(),UnknownFieldSet(Map()))
          |Response(2,0,Vector(),UnknownFieldSet(Map()))
        """.stripMargin
      )
  }

  testGrpc("server streaming call fails with a server error") { state =>
    state.stub
      .serverStreaming(Request(Request.Scenario.ERROR_NOW))
      .toListL
      .expectServerSilentException
  }

  testGrpc("server streaming call sends error after successful values") { state =>
    state.stub
      .serverStreaming(Request(Request.Scenario.ERROR_AFTER))
      .map(Right(_))
      .onErrorHandle(Left(_))
      .toListL
      .expectDiff(
        """
          |Right(Response(1,0,Vector(),UnknownFieldSet(Map())))
          |Right(Response(2,0,Vector(),UnknownFieldSet(Map())))
          |Left(io.grpc.StatusRuntimeException: INTERNAL: SILENT)
        """.stripMargin
      )
  }

  testGrpc("server streaming call times out") { state =>
    state.stub
      .serverStreaming(Request(Request.Scenario.DELAY))
      .toListL
      .timeout(1.second)
      .expectTimeoutException
  }

  testGrpc("client streaming call responds successfully") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onCompleteL
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .clientStreaming(clientRequests)
        .expectResponseValue(3)
    }
  }

  testGrpc("client streaming call fails with a server error") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onNextL(Request(Request.Scenario.ERROR_NOW), 200)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 200)
      _ <- stream.onCompleteL
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .clientStreaming(clientRequests)
        .expectServerSilentException
    }
  }

  testGrpc("client streaming call fails with a client error") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onErrorL(SilentException())
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .clientStreaming(clientRequests)
        .expectClientSilentException
    }
  }

  testGrpc("client streaming call times out") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.DELAY), 0)
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .clientStreaming(clientRequests)
        .timeout(1.seconds)
        .expectTimeoutException
    }
  }

  testGrpc("bidirectional streaming call succeeds") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onCompleteL
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .toListL
        .map(_.map(_.out))
        .expectDiff("1")
    }
  }

  testGrpc("bidirectional streaming call fails with a client error") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onErrorL(SilentException())
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .toListL
        .expectClientSilentException
    }
  }

  testGrpc("bidirectional streaming call fails with a server error") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.ERROR_NOW), 0)
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .toListL
        .expectServerSilentException
    }
  }

  testGrpc("bidirectional streaming call times out") { state =>
    def sendRequests(stream: ClientStream[Request]) = for {
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.OK), 0)
      _ <- stream.onNextL(Request(Request.Scenario.DELAY), 0)
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .toListL
        .timeout(1.seconds)
        .expectTimeoutException
    }
  }

  /*
  testGrpc("numbers request supports backpressure") { state =>
    def sendRequests(stream: ClientStream[Number]) = for {
      _ <- stream.onNextL(Number.of(???), 0)
      _ <- stream.onCompleteL
    } yield ()

    state.withClientStream(sendRequests) { clientRequests =>
      state.stub
        .requestPressure(clientRequests)
        .void
    }
  }
   */

  implicit class ExpectTaskOps[T](t: Task[T]) {

    /** Expect a silent (benign) exception thrown by the server */
    def expectServerSilentException(implicit loc: Location): Task[Unit] = {
      t.redeem(
        {
          case err: StatusRuntimeException =>
            // Don't check cause because for some reason grpc-java makes it null
            assertEquals(err.getMessage, "INTERNAL: SILENT")
          case err => fail(s"Expected status runtime exception!", err)
        },
        value => fail(s"Expected silent exception in server, obtained value $value!")
      )
    }

    /** Expect a silent (benign) exception thrown by the client */
    def expectClientSilentException(implicit loc: Location): Task[Unit] = {
      t.redeem(
        {
          case err: StatusRuntimeException =>
            assertEquals(err.getStatus.getCode, Status.Code.CANCELLED)
            assertNoDiff(
              err.getStatus.getCause.toString,
              "scalapb.monix.grpc.testservice.SilentException: SILENT"
            )
          case err => fail(s"Expected status runtime exception!", err)
        },
        value => fail(s"Expected silent exception in client, obtained value $value!")
      )
    }

    def expectTimeoutException(implicit loc: Location): Task[Unit] = {
      t.redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        value => fail(s"Expected timeout exception, obtained value $value!")
      )
    }

    def expectResponseValue(expected: Int)(implicit v: T =:= Response): Task[Unit] =
      t.map(v.apply(_)).map(r => assertEquals(r.out, expected))

    def expectDiff(expected: String)(implicit
        v: T <:< List[_],
        loc: Location
    ): Task[Unit] = t.map { value =>
      val obtained = value.mkString(System.lineSeparator())
      assertNoDiff(obtained, expected)
    }
  }
}

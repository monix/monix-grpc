# monix-grpc

[![Continuous Integration](https://github.com/jvican/monix-grpc/actions/workflows/ci.yml/badge.svg)](https://github.com/jvican/monix-grpc/actions/workflows/ci.yml)

A library to write grpc client and servers in Scala using [Monix][].

### Design Goals

- Support all unary and streaming flavors of grpc calls
- Provide nimble and fast Monix building blocks on top of `grpc-java`
- Implement efficient cancellation, buffering and back-pressure out-of-the-box
- Embrace and extend `grpc-java`'s' API safely and idiomatically instead of creating a new API

### Installation

- [Install with sbt](examples/sbt-installation/README.md)

### Team

The current maintainers (people who can merge pull requests) are:

- Jorge Vicente Cantero - [@jvican][]
- Boris Smidt - [@borissmidt][]

### Credits

- Powered by [ScalaPB][] and [grpc/grpc-java][].
- Inspired by [salesforce/reactive-grpc][], [typelevel/fs2-grpc][] and [scalapb/zio-grpc][] among others.

[@jvican]: https://github.com/jvican
[@borissmidt]: https://github.com/borissmidt
[Monix]: https://github.com/monix/monix
[ScalaPB]: https://scalapb.github.io
[grpc/grpc-java]: https://github.com/grpc/grpc-java
[scalapb/zio-grpc]: https://github.com/scalapb/zio-grpc
[typelevel/fs2-grpc]: https://github.com/typelevel/fs2-grpc
[salesforce/reactive-grpc]: https://github.com/salesforce/reactive-grpc
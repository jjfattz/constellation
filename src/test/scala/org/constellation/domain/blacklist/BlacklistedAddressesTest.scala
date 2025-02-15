package org.constellation.domain.blacklist

import cats.effect.{ContextShift, IO}
import org.constellation.ConstellationExecutionContext
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class BlacklistedAddressesTest extends AnyFreeSpec with BeforeAndAfter with Matchers {

  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private var blacklistAddresses: BlacklistedAddresses[IO] = _

  before {
    blacklistAddresses = BlacklistedAddresses[IO]
  }

  "contains" - {
    "returns true if address exists" in {
      val address: String = "abcd1234"

      blacklistAddresses.add(address).unsafeRunSync()

      blacklistAddresses.contains(address).unsafeRunSync() shouldBe true
    }

    "return false if address doesn't exist" in {
      val address: String = "abcd1234"

      blacklistAddresses.add(address).unsafeRunSync()

      blacklistAddresses.contains("abcd").unsafeRunSync() shouldBe false
    }
  }

  "add" - {
    "should add address to blacklist" in {
      val address: String = "abcd1234"

      blacklistAddresses.add(address).unsafeRunSync()

      blacklistAddresses.get.unsafeRunSync().contains(address) shouldBe true
    }
  }

  "addAll" - {
    "should add all addresses to blacklist" in {
      val addresses = List("abcd1234", "efgh5678")

      val initial = blacklistAddresses.get.unsafeRunSync().size

      blacklistAddresses.addAll(addresses).unsafeRunSync()

      blacklistAddresses.get.unsafeRunSync().size shouldBe 2 + initial
    }
  }

  "clear" - {
    "should remove all addresses from blacklist" in {
      val addresses = List("abcd1234", "efgh5678")

      blacklistAddresses.addAll(addresses).unsafeRunSync()

      blacklistAddresses.clear.unsafeRunSync()

      blacklistAddresses.get.unsafeRunSync().size shouldBe 0
    }
  }
}

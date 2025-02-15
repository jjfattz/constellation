package org.constellation.rewards

import scala.util.Random

trait StardustCollective {
  def getAddress(): String
  def weightByStardust(ignore: Set[String])(distribution: Map[String, Double]): Map[String, Double]
}

object StardustCollective extends StardustCollective {
  private final val percentage = 10 // Stardust takes 10% of each reward

  /*
   * TODO: The address was generated with the createDecidatedAddress() method and should be valid.
   *  Next step would be to extract the method which validates the address and validate it in unit tests.
   *  (for now we have method which validates whole Transaction)
   *  It should succeed as validation only checks if address starts with "DAG" and has more than 30 characters in total.
   *  It includes STARDUSTCOLLECTIVE part right after DAG to make sure that KeyPair can't generate it.
   *  Also the production ready solution should be discussed anyway.
   */
  private final val address: String = "DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS"

  def getAddress(): String = address

  def weightByStardust(ignore: Set[String])(distribution: Map[String, Double]): Map[String, Double] = {
    val stardustWeights = (distribution -- ignore)
      .mapValues(_ * (percentage.toDouble / 100.0))

    val weighted = distribution.transform {
      case (address, reward) if ignore.contains(address) => reward
      case (address, reward)                             => reward - stardustWeights(address)
    }

    val totalStardustReward = stardustWeights.values.sum

    weighted + (address -> totalStardustReward)
  }

  def createDecidatedAddress(): String =
    "DAG" + "STARDUSTCOLLECTIVE" + Random.alphanumeric.filter(_.isLetter).take(30).mkString.toUpperCase
}

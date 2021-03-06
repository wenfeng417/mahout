/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.sparkbindings.blas

import org.apache.mahout.math._
import org.apache.mahout.sparkbindings.drm._
import org.apache.mahout.math.scalabindings._
import RLikeOps._
import collection._
import JavaConversions._
import org.apache.mahout.sparkbindings.drm.plan.OpAtA
import org.apache.hadoop.io.Writable
import scala.reflect.ClassTag

/**
 * Collection of algorithms to compute X' times X
 */
object AtA {

  val maxInMemNCol = System.getProperty("mahout.math.AtA.maxInMemNCol", "2000").toInt
  maxInMemNCol.ensuring(_ > 0, "Invalid A'A in-memory setting for optimizer")

  /** Materialize A'A operator */
  def at_a(operator: OpAtA[_], srcRdd: DrmRddInput[_] ): DrmRddInput[Int] = {

    if (operator.ncol <= maxInMemNCol) {
      // If we can comfortably fit upper-triangular operator into a map memory, we will run slim
      // algorithm with upper-triangular accumulators in maps. 
      val inCoreA = at_a_slim(srcRdd = srcRdd, operator = operator)
      val drmRdd = parallelizeInCore(inCoreA, numPartitions = 1)(sc = srcRdd.sparkContext)
      new DrmRddInput(rowWiseSrc = Some(inCoreA.ncol, drmRdd))
    } else {
      // Otherwise, we need to run a distributed, big version
      new DrmRddInput(rowWiseSrc=Some(operator.ncol, at_a_nongraph(srcRdd = srcRdd, operator = operator)))

    }
  }


  /**
   * Computes A' * A for tall but skinny A matrices. Comes up a lot in SSVD and ALS flavors alike.
   * @return
   */
  def at_a_slim(operator: OpAtA[_], srcRdd: DrmRdd[_] ): Matrix = {

    val ncol = operator.ncol
    // Compute backing vector of tiny-upper-triangular accumulator accross all the data.
    val resSym = srcRdd.mapPartitions(pIter => {

      val ut = new UpperTriangular(ncol)

      // Strategy is to add to an outer product of each row to the upper triangular accumulator.
      pIter.foreach({case(k,v) =>

        // Use slightly various traversal strategies over dense vs. sparse source.
        if (v.isDense) {

          // Update upper-triangular pattern only (due to symmetry).
          // Note: Scala for-comprehensions are said to be fairly inefficient this way, but this is
          // such spectacular case they were deesigned for.. Yes I do observe some 20% difference
          // compared to while loops with no other payload, but the other payload is usually much
          // heavier than this overhead, so... I am keeping this as is for the time being.

          for (row <- 0 until v.length; col <- row until v.length)
            ut(row, col) = ut(row, col) + v(row) * v(col)

        } else {

          // Sparse source.
          v.nonZeroes().view

              // Outer iterator iterates over rows of outer product.
              .foreach(elrow => {

            // Inner loop for columns of outer product.
            v.nonZeroes().view

                // Filter out non-upper nonzero elements from the double loop.
                .filter(_.index >= elrow.index)

                // Incrementally update outer product value in the uppper triangular accumulator.
                .foreach(elcol => {

              val row = elrow.index
              val col = elcol.index
              ut(row, col) = ut(row, col) + elrow.get() * elcol.get()

            })
          })

        }
      })

      Iterator(dvec(ddata = ut.getData): Vector)
    })

        .collect()
        .reduce(_ += _)

    new DenseSymmetricMatrix(resSym)
  }

  /** The version of A'A that does not use GraphX */
  def at_a_nongraph(operator: OpAtA[_], srcRdd: DrmRdd[_] ): DrmRdd[Int] =
    throw new UnsupportedOperationException

}

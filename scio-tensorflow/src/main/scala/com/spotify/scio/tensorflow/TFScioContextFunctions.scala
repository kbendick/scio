/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.tensorflow

import java.nio.file.Files

import com.spotify.scio.ScioContext
import com.spotify.scio.values.{DistCache, SCollection}
import org.apache.beam.sdk.io.Compression
import org.tensorflow.example.Example
import org.tensorflow.metadata.v0.Schema

class TFScioContextFunctions(val self: ScioContext) extends AnyVal {

  /**
   * Get an SCollection for a TensorFlow TFRecord file. Note that TFRecord files are not
   * splittable. The recommended record encoding is `org.tensorflow.example.Example` protocol
   * buffers (which contain `org.tensorflow.example.Features` as a field) serialized as bytes.
   * @group input
   */
  def tfRecordFile(path: String,
                   compression: Compression = Compression.AUTO): SCollection[Array[Byte]] =
    self.read(TFRecordIO(path))(TFRecordIO.ReadParam(compression))

  /**
   * Get an SCollection of `org.tensorflow.example.Example` from a TensorFlow TFRecord file
   * encoded as serialized `org.tensorflow.example.Example` protocol buffers.
   * @group input
   */
  def tfRecordExampleFile(path: String,
                          compression: Compression = Compression.AUTO): SCollection[Example] =
    self.read(TFExampleIO(path))(TFExampleIO.ReadParam(compression))

  /**
   * Get an SCollection of `org.tensorflow.example.Example` from a TensorFlow TFRecord file
   * encoded as serialized `org.tensorflow.example.Example` protocol buffers, along with the
   * remotely stored `org.tensorflow.metadata.v0.Schema` object available in a DistCache.
   * @group input
   */
  @deprecated("Schema inference will be removed. We recommend using TensorFlow Data Validation",
              "Scio 0.7.0")
  def tfRecordExampleFileWithSchema(
    path: String,
    schemaFilename: String,
    compression: Compression = Compression.AUTO): (SCollection[Example], DistCache[Schema]) = {

    val distCache = self.distCache(schemaFilename) { file =>
      Schema.parseFrom(Files.readAllBytes(file.toPath))
    }

    (tfRecordExampleFile(path, compression), distCache)
  }
}

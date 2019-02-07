/*
 * Copyright 2016 Spotify AB.
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
package com.spotify.scio.sql

import com.spotify.scio.values._
import com.spotify.scio.coders._
import com.spotify.scio.schemas.{Record, ScalarWrapper, Schema, SchemaMaterializer}
import org.apache.beam.sdk.values._
import org.apache.beam.sdk.extensions.sql.SqlTransform
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv
import org.apache.beam.sdk.extensions.sql.impl.schema.BeamPCollectionTable
import org.apache.beam.sdk.extensions.sql.impl.schema.BaseBeamTable
import org.apache.beam.sdk.schemas.{Schema => BSchema}
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils
import com.google.common.collect.ImmutableMap

import scala.collection.JavaConverters._
import scala.language.experimental.macros

// TODO: could be a PTransform
sealed trait Query[I, O] {
  val query: String
  def run(c: SCollection[I]): SCollection[O]
}

object Query {

  val PCOLLECTION_NAME = "PCOLLECTION"

  private def printContent(fs: List[BSchema.Field], prefix: String = ""): String = {
    fs.map { f =>
        val nullable = if (f.getNullable) "YES" else "NO"
        // val out =  s"${prefix}${f.getName}\t${f.getType.getTypeName}\t$nullable\n"
        val `type` = f.getType
        val typename =
          `type`.getTypeName match {
            case t @ BSchema.TypeName.ARRAY =>
              s"${`type`.getCollectionElementType.getTypeName}[]"
            case t => t
          }
        val out =
          f"│ ${prefix + f.getName}%-40s │ ${typename}%-8s │ $nullable%-8s │%n"
        val underlying =
          if (f.getType.getTypeName == BSchema.TypeName.ROW)
            printContent(f.getType.getRowSchema.getFields.asScala.toList, s"${prefix}${f.getName}.")
          else ""

        out + underlying
      }
      .mkString("")
  }

  private def prettyPrint(fs: List[BSchema.Field]): String = {
    val header =
      f"""
      |┌──────────────────────────────────────────┬──────────┬──────────┐
      |│ NAME                                     │ TYPE     │ NULLABLE │
      |├──────────────────────────────────────────┼──────────┼──────────┤%n""".stripMargin.drop(1)
    val footer =
      f"""
      |└──────────────────────────────────────────┴──────────┴──────────┘%n""".stripMargin.trim

    header + printContent(fs) + footer
  }

  // Beam is anoigly verbose when is parses SQL queries.
  // This function makes is silent.
  private def silence[A](a: => A): A = {
    val prop = "org.slf4j.simpleLogger.defaultLogLevel"
    val ll = System.getProperty(prop)
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "ERROR")
    val x = a
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", ll)
    x
  }

  def typecheck[I: Schema, O: Schema](q: Query[I, O]): Either[String, Query[I, O]] = {
    val schema: BSchema = SchemaMaterializer.fieldType(Schema[I]).getRowSchema()

    val table = new BaseBeamTable(schema) {
      def buildIOReader(begin: PBegin): PCollection[Row] = ???
      def buildIOWriter(input: PCollection[Row]): POutput = ???
    }

    val sqlEnv =
      BeamSqlEnv.readOnly(Query.PCOLLECTION_NAME, ImmutableMap.of(Query.PCOLLECTION_NAME, table))

    val expectedSchema: BSchema =
      Schema[O] match {
        case s @ Record(_, _, _) =>
          SchemaMaterializer.fieldType(s).getRowSchema()
        case _ =>
          SchemaMaterializer.fieldType(Schema[ScalarWrapper[O]]).getRowSchema()
      }

    scala.util
      .Try(silence(sqlEnv.parseQuery(q.query)))
      .toEither
      .left
      .map { ex =>
        org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage(ex)
      }
      .map { q =>
        CalciteUtils.toSchema(q.getRowType)
      }
      .flatMap {
        case inferedSchema if inferedSchema.typesEqual(expectedSchema) =>
          Right(q)
        case inferedSchema =>
          val message =
            s"""
          |Infered schema for query is not compatible with the expected schema.
          |
          |Query:
          |${q.query}
          |
          |PCOLLECTION schema:
          |${prettyPrint(schema.getFields.asScala.toList)}
          |Query result schema (infered):
          |${prettyPrint(inferedSchema.getFields.asScala.toList)}
          |Expected schema:
          |${prettyPrint(expectedSchema.getFields.asScala.toList)}
        """.stripMargin
          Left(message)
      }
  }

  def row[I: Schema](q: String): Query[I, Row] =
    new Query[I, Row] {
      val query = q
      def run(c: SCollection[I]) = {
        val scoll = c.setSchema(Schema[I])
        val sqlEnv = BeamSqlEnv.readOnly(
          PCOLLECTION_NAME,
          ImmutableMap.of(PCOLLECTION_NAME, new BeamPCollectionTable(scoll.internal)))
        // Will it support UDF (see SqlTransform.expand) ?
        val q = sqlEnv.parseQuery(query)
        val schema = CalciteUtils.toSchema(q.getRowType)
        scoll.applyTransform[Row](SqlTransform.query(query))(Coder.row(schema))
      }
    }

  def of[I: Schema, O: Schema](q: String): Query[I, O] =
    new Query[I, O] {
      val query = q
      def run(s: SCollection[I]): SCollection[O] = {
        import org.apache.beam.sdk.schemas.SchemaCoder
        val (schema, to, from) = SchemaMaterializer.materialize(s.context, Schema[O])
        val coll: SCollection[Row] = Query.row[I](query).run(s)
        coll.map[O](r => from(r))(Coder.beam(SchemaCoder.of(schema, to, from)))
      }
    }

  def tsql[I: Schema, O: Schema](query: String): Query[I, O] =
    macro com.spotify.scio.sql.QueryMacros.tsqlImpl[I, O]
}

object QueryMacros {
  import scala.reflect.macros.blackbox
  def tsqlImpl[I, O](c: blackbox.Context)(query: c.Expr[String])(
    iSchema: c.Expr[Schema[I]],
    oSchema: c.Expr[Schema[O]]): c.Expr[Query[I, O]] = {
    import c.universe._

    val queryTree = c.untypecheck(query.tree.duplicate)
    val sInTree = c.untypecheck(iSchema.tree.duplicate)
    val sOutTree = c.untypecheck(oSchema.tree.duplicate)

    val (sIn, sOut) =
      c.eval(c.Expr[(Schema[I], Schema[O])](q"($sInTree, $sOutTree)"))

    val sq =
      queryTree match {
        case Literal(Constant(q: String)) =>
          Query.of(q)(sIn, sOut)
        case _ =>
          c.abort(c.enclosingPosition, s"Expression $queryTree does not evaluate to a constant")
      }

    Query
      .typecheck(sq)(sIn, sOut)
      .fold(
        err => c.abort(c.enclosingPosition, err), { t =>
          val out =
            q"_root_.com.spotify.scio.sql.Query.of($query)($iSchema, $oSchema)"
          c.Expr[Query[I, O]](out)
        }
      )
  }
}
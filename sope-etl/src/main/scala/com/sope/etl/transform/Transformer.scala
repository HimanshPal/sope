package com.sope.etl.transform

import com.sope.etl.AutoPersistProperty
import com.sope.etl.transform.exception.YamlDataTransformException
import com.sope.etl.transform.model._
import com.sope.etl.transform.model.action.JoinAction
import com.sope.spark.sql.dsl._
import com.sope.utils.Logging
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col
import org.apache.spark.storage.StorageLevel

import scala.util.{Failure, Success, Try}

/**
  * Contains logic for building Spark SQL transformations
  *
  * @author mbadgujar
  */
class Transformer(file: String, inputMap: Map[String, DataFrame], transformations: Seq[DFTransformation]) extends Logging {


  private case class InputSource(name: String, isUsedForJoin: Boolean, joinColumns: Option[Seq[String]])

  private val autoPersistSetting = Option(System.getProperty(AutoPersistProperty)).fold(true)(_.toBoolean)
  private var sourceDFMap: Map[String, DataFrame] = inputMap

  // Generate the input sources for the transformation
  private lazy val inputSources = transformations
    .flatMap(transform => transform.actions.fold(Nil: Seq[InputSource])(actions =>
      actions.foldLeft(Nil: Seq[InputSource]) {
        case (inputs, action) =>
          val (isJoinAction, joinColumns) =
            action match {
              case ja: JoinAction if !ja.isExpressionBased => (true, Some(ja.joinColumns))
              case _ => (false, None)
            }
          inputs ++
            (InputSource(transform.source, isJoinAction, joinColumns) +:
              action.inputAliases.map(alias => InputSource(alias, isJoinAction, joinColumns)))
      }))

  /**
    * Check if the alias that is to be persisted can be
    * pre sorted if used in multiple joins using same columns
    *
    * @param alias Transformation alias
    * @return Sort columns
    */
  private def preSort(alias: String): Option[Seq[String]] = {
    val joinSources = inputSources
      .filter(source => source.name == alias && source.isUsedForJoin)
      .map(source => source.joinColumns.get.sorted)
    if (joinSources.nonEmpty) Some(joinSources.maxBy(_.mkString(","))) else None
  }

  // Initialize the auto persist mapping for sources
  val autoPersistList: Seq[String] = {
    val persistList = inputSources
      .map(_.name -> 1)
      .groupBy(_._1)
      .map { case (k, v) => (k, v.size) }
      .filter(_._2 > 1).keys
      .toSeq
    if (autoPersistSetting) persistList.distinct else Nil
  }


  // gets dataframe from provided alias
  private def getDF(alias: String): DataFrame = {
    if (sourceDFMap.isDefinedAt(alias)) {
      val autoPersist = autoPersistList.contains(alias)
      sourceDFMap(alias).storageLevel match {
        case level: StorageLevel if level == StorageLevel.NONE && `autoPersist` =>
          logWarning(s"Auto persisting transformation: '$alias' in Memory only mode")
          val persisted = (preSort(alias) match {
            case Some(sortCols) =>
              logWarning(s"Persisted transformation: '$alias' will be pre-sorted on columns: ${sortCols.mkString(", ")}")
              sourceDFMap(alias).sort(sortCols.map(col): _*)
            case None =>
              sourceDFMap(alias)
          }).persist(StorageLevel.MEMORY_ONLY)
          sourceDFMap = sourceDFMap updated(alias, persisted)
          persisted
        case _ => sourceDFMap(alias)
      }
    }
    else
      throw new YamlDataTransformException(s"Alias: $alias not found")
  }

  /**
    * Applies the provided list of transformations on the sources (dataframes)
    *
    * @return Transformed Dataframes
    */
  def transform: Seq[(String, DataFrame)] = {

    logDebug("AUTO persist set: " + autoPersistSetting)
    logDebug("AUTO persist data list: " + autoPersistList.mkString(", "))

    transformations.map(dfTransform => {
      logInfo(s"Applying transformation: ${dfTransform.getAlias}")
      val sourceDF = getDF(dfTransform.source)
      // coalesce function
      val coalesceFunc = (df: DataFrame) => if (dfTransform.coalesce == 0) df else df.coalesce(dfTransform.coalesce)
      // if sql transform apply sql or perform provided action transformation
      val transformedDF = if (dfTransform.isSQLTransform) {
        sourceDF.createOrReplaceTempView(dfTransform.source)
        sourceDF.sqlContext.sql(dfTransform.sql.get)
      } else {
        Try {
          dfTransform.actions.get.foldLeft(NoOp()) {
            case (transformed, transformAction) => transformed + transformAction(transformAction.inputAliases.map(getDF): _*)
          } --> sourceDF
        } match {
          case Success(df) => df
          case Failure(e) =>
            logError(s"Transformation failed for alias: ${dfTransform.getAlias} in $file file")
            throw e
        }
      }.transform(coalesceFunc)

      // Add alias to dataframe
      val transformedWithAliasDF = {
        dfTransform.persistLevel.fold(transformedDF)(level => {
          logInfo(s"Transformation ${dfTransform.getAlias} is configured to be persisted at level: $level")
          transformedDF.persist(StorageLevel.fromString(level.toUpperCase))
        })
      }.alias(dfTransform.getAlias)
      // Update Map
      sourceDFMap = sourceDFMap updated(dfTransform.getAlias, transformedWithAliasDF)
      (dfTransform.getAlias, transformedWithAliasDF)
    })
  }

}

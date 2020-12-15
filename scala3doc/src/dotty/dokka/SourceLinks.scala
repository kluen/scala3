package dotty.dokka

import java.nio.file.Path
import java.nio.file.Paths
import liqp.Template
import dotty.dokka.model.api._
import dotty.tools.dotc.core.Contexts.Context

def pathToString(p: Path) = p.toString.replace('\\', '/')

trait SourceLink:
  val path: Option[Path] = None
  def render(path: Path, operation: String, line: Option[Int]): String

case class PrefixedSourceLink(val myPath: Path, nested: SourceLink) extends SourceLink:
  val myPrefix = pathToString(myPath)
  override val path = Some(myPath)
  override def render(path: Path, operation: String, line: Option[Int]): String =
    nested.render(myPath.relativize(path), operation, line)


case class TemplateSourceLink(val urlTemplate: Template) extends SourceLink:
  override val path: Option[Path] = None
  override def render(path: Path, operation: String, line: Option[Int]): String =
    val config = java.util.HashMap[String, Object]()
    config.put("path", pathToString(path))
    line.foreach(l => config.put("line", l.toString))
    config.put("operation", operation)

    urlTemplate.render(config)

case class WebBasedSourceLink(prefix: String, revision: String, subPath: String) extends SourceLink:
  override val path: Option[Path] = None
  override def render(path: Path, operation: String, line: Option[Int]): String =
    val action = if operation == "view" then "blob" else operation
    val linePart = line.fold("")(l => s"#L$l")
    s"$prefix/$action/$revision$subPath/$path$linePart"

object SourceLink:
  val SubPath = "([^=]+)=(.+)".r
  val KnownProvider = raw"(\w+):\/\/([^\/#]+)\/([^\/#]+)(\/[^\/#]+)?(#.+)?".r
  val BrokenKnownProvider = raw"(\w+):\/\/.+".r
  val ScalaDocPatten = raw"€\{(TPL_NAME|TPL_NAME|FILE_PATH|FILE_EXT|FILE_LINE|FILE_PATH_EXT)\}".r
  val SupportedScalaDocPatternReplacements = Map(
    "€{FILE_PATH_EXT}" -> "{{ path }}",
    "€{FILE_LINE}" -> "{{ line }}"
  )

  def githubPrefix(org: String, repo: String) = s"https://github.com/$org/$repo"

  def gitlabPrefix(org: String, repo: String) = s"https://gitlab.com/$org/$repo/-"


  private def parseLinkDefinition(s: String): Option[SourceLink] = ???

  def parse(string: String, revision: Option[String]): Either[String, SourceLink] =
    def asTemplate(template: String) =
       try Right(TemplateSourceLink(Template.parse(template))) catch
            case e: RuntimeException =>
              Left(s"Failed to parse template: ${e.getMessage}")

    string match
      case KnownProvider(name, organization, repo, rawRevision, rawSubPath) =>
        val subPath = Option(rawSubPath).fold("")("/" + _.drop(1))
        val pathRev = Option(rawRevision).map(_.drop(1)).orElse(revision)

        def withRevision(template: String => SourceLink) =
          pathRev.fold(Left(s"No revision provided"))(r => Right(template(r)))

        name match
          case "github" =>
            withRevision(rev =>
              WebBasedSourceLink(githubPrefix(organization, repo), rev, subPath))
          case "gitlab" =>
            withRevision(rev =>
              WebBasedSourceLink(gitlabPrefix(organization, repo), rev, subPath))
          case other =>
            Left(s"'$other' is not a known provider, please provide full source path template.")

      case SubPath(prefix, config) =>
        parse(config, revision) match
          case l: Left[String, _] => l
          case Right(_:PrefixedSourceLink) =>
            Left(s"Source path $string has duplicated subpath setting (scm template can not contains '=')")
          case Right(nested) =>
            Right(PrefixedSourceLink(Paths.get(prefix), nested))
      case BrokenKnownProvider("gitlab" | "github") =>
        Left(s"Does not match known provider syntax: `<name>://organization/repository`")
      case scaladocSetting if ScalaDocPatten.findFirstIn(scaladocSetting).nonEmpty =>
        val all = ScalaDocPatten.findAllIn(scaladocSetting)
        val (supported, unsupported) = all.partition(SupportedScalaDocPatternReplacements.contains)
        if unsupported.nonEmpty then Left(s"Unsupported patterns from scaladoc format are used: ${unsupported.mkString(" ")}")
        else asTemplate(supported.foldLeft(string)((template, pattern) =>
          template.replace(pattern, SupportedScalaDocPatternReplacements(pattern))))

      case template => asTemplate(template)


type Operation = "view" | "edit"

case class SourceLinks(links: Seq[SourceLink], projectRoot: Path):
  def pathTo(rawPath: Path, line: Option[Int] = None, operation: Operation = "view"): Option[String] =
    def resolveRelativePath(path: Path) =
      links
        .find(_.path.forall(p => path.startsWith(p)))
        .map(_.render(path, operation, line))

    if rawPath.isAbsolute then
      if rawPath.startsWith(projectRoot) then resolveRelativePath(projectRoot.relativize(rawPath))
      else None
    else resolveRelativePath(rawPath)

  def pathTo(member: Member): Option[String] =
    member.sources.flatMap(s => pathTo(Paths.get(s.path), Option(s.lineNumber).map(_ + 1)))

object SourceLinks:

  val usage =
    """Source links provide a mapping between file in documentation and code repository.
      |
      |Accepted formats:
      |<sub-path>=<source-link>
      |<source-link>
      |
      |where <source-link> is one of following:
      | - `github://<organization>/<repository>[/revision][#subpath]`
      |     will match https://github.com/$organization/$repository/[blob|edit]/$revision[/$subpath]/$filePath[$lineNumber]
      |     when revision is not provided then requires revision to be specified as argument for scala3doc
      | - `gitlab://<organization>/<repository>`
      |     will match https://gitlab.com/$organization/$repository/-/[blob|edit]/$revision[/$subpath]/$filePath[$lineNumber]
      |     when revision is not provided then requires revision to be specified as argument for scala3doc
      | - <scaladoc-template>
      | - <template>
      |
      |<scaladoc-template> is a format for `doc-source-url` parameter scaladoc.
      |NOTE: We only supports `€{FILE_PATH_EXT}` and €{FILE_LINE} patterns
      |
      |<template> is a liqid template string that can accepts follwoing arguments:
      | - `operation`: either "view" or "edit"
      | - `path`: relative path of file to provide link to
      | - `line`: optional parameter that specify line number within a file
      |
      |
      |Template can defined only by subset of sources defined by path prefix represented by `<sub-path>`.
      |In such case paths used in templates will be relativized against `<sub-path>`""".stripMargin

  def load(
      configs: Seq[String],
      revision: Option[String],
      projectRoot: Path)(
      using Context): SourceLinks =
    // TODO ...
    val mappings = configs.map(str => str -> SourceLink.parse(str, revision))

    val errors = mappings.collect {
      case (template, Left(message)) =>
        s"'$template': $message"
    }.mkString("\n")

    if errors.nonEmpty then report.warning(
      s"""Following templates has invalid format:
         |$errors
         |
         |$usage
         |""".stripMargin
      )

    SourceLinks(mappings.collect {case (_, Right(link)) => link}, projectRoot)

  def load(using ctx: DocContext): SourceLinks =
    load(
      ctx.args.sourceLinks,
      ctx.args.revision,
      // TODO (https://github.com/lampepfl/scala3doc/issues/240): configure source root
      Paths.get("").toAbsolutePath
    )
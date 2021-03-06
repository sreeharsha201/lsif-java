package com.sourcegraph.lsif_java

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import com.sourcegraph.lsif_java.buildtools.BuildTool
import moped.annotations.Description
import moped.annotations.ExampleValue
import moped.annotations.Inline
import moped.annotations.TrailingArguments
import moped.cli.Application
import moped.cli.Command
import moped.cli.CommandParser
import moped.internal.reporters.Levenshtein
import os.CommandResult
import os.Inherit
import os.Shellable

@Description(
  "Generates an LSIF index for the Java build of a provided workspace directory."
)
case class IndexCommand(
    @Description("The path where to generate the LSIF index.") output: Path =
      Paths.get("dump.lsif"),
    @Description(
      "The directory where to generate SemanticDB files. " +
        "Defaults to a build-specific path. " +
        "For example, the default value for Gradle is 'build/semanticdb-targetroot' and for Maven it's 'target/semanticdb-targetroot'"
    ) targetroot: Option[Path] = None,
    @Description(
      "Whether to enable the -verbose flag in the SemanticDB compiler plugin."
    ) verbose: Boolean = false,
    @Description(
      "Whether to enable the -text:on flag in the SemanticDB compiler plugin."
    ) text: Boolean = false,
    @Description(
      "Explicitly specify which build tool to use. " +
        "By default, the build tool is automatically detected. " +
        "Use this flag if the automatic build tool detection is not working correctly."
    )
    @ExampleValue("Gradle") buildTool: Option[String] = None,
    @Description(
      "Whether to enable remove generated temporary files on exit."
    ) cleanup: Boolean = true,
    @Description(
      "The build command to use to compile all sources. " +
        "Defaults to a build-specific command. For example, the default command for Maven command is 'clean verify -DskipTests'." +
        "To override the default, pass in the build command after a double dash: 'lsif-java -- compile'"
    )
    @TrailingArguments() buildCommand: List[String] = Nil,
    @Inline
    app: Application = Application.default
) extends Command {

  def process(shellable: String*): CommandResult = {
    app.info(shellable.mkString(" "))
    app
      .process(Shellable(shellable))
      .call(
        check = false,
        stdout = Inherit,
        stderr = Inherit,
        cwd = workingDirectory
      )
  }

  def textFlag: String =
    if (text)
      "-text:on"
    else
      ""
  def verboseFlag: String =
    if (verbose)
      "-verbose:on"
    else
      ""

  def workingDirectory: Path = AbsolutePath.of(app.env.workingDirectory)
  def finalTargetroot(default: Path): Path =
    AbsolutePath.of(targetroot.getOrElse(default), workingDirectory)
  def finalBuildCommand(default: List[String]): List[String] =
    if (buildCommand.isEmpty)
      default
    else
      buildCommand

  override def run(): Int = {
    val allBuildTools = BuildTool.all(this)
    val usedBuildTools = allBuildTools.filter(_.usedInCurrentDirectory())
    val matchingBuildTools = usedBuildTools.filter(tool =>
      buildTool match {
        case Some(explicitName) =>
          tool.name.compareToIgnoreCase(explicitName) == 0
        case None =>
          true
      }
    )

    matchingBuildTools match {
      case Nil =>
        buildTool match {
          case Some(explicit) if usedBuildTools.nonEmpty =>
            val toFix =
              Levenshtein
                .closestCandidate(explicit, usedBuildTools.map(_.name)) match {
                case Some(closest) =>
                  s"Did you mean --build-tool=$closest?"
                case None =>
                  "To fix this problem, run again with the --build-tool flag set to one of the detected build tools."
              }
            val autoDetected = usedBuildTools.map(_.name).mkString(", ")
            app.error(
              s"Automatically detected the build tool(s) $autoDetected but none of them match the explicitly provided flag '--build-tool=$explicit'. " +
                toFix
            )
          case _ =>
            if (Files.isDirectory(workingDirectory)) {
              val buildToolNames = allBuildTools.map(_.name).mkString(", ")
              app.error(
                s"No build tool detected in workspace '$workingDirectory'. " +
                  s"At the moment, the only supported build tools are: $buildToolNames."
              )
            } else {
              val cause =
                if (Files.exists(workingDirectory)) {
                  s"Workspace '$workingDirectory' is not a directory"
                } else {
                  s"The directory '$workingDirectory' does not exist"
                }
              app.error(
                cause +
                  s". To fix this problem, make sure the working directory is an actual directory."
              )
            }
        }
        1
      case tool :: Nil =>
        val generateSemanticdbResult = tool.generateSemanticdb()
        if (!Files.isDirectory(tool.targetroot)) {
          generateSemanticdbResult.exitCode
        } else {
          val generateLsifResult = process(
            "lsif-semanticdb",
            s"--semanticdbDir=${tool.targetroot}"
          )
          generateSemanticdbResult.exitCode + generateLsifResult.exitCode
        }
      case many =>
        val names = many.map(_.name).mkString(", ")
        app.error(
          s"Multiple build tools detected: $names. " +
            s"To fix this problem, use the '--build-tools=BUILD_TOOL_NAME' flag to specify which build tool to run."
        )
        1
    }
  }
}

object IndexCommand {
  val default: IndexCommand = IndexCommand()
  implicit val parser: CommandParser[IndexCommand] = CommandParser
    .derive(default)
}

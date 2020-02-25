/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.util.concurrent.{ ExecutorService, Executors }
import ClassLoaderClose.close

import sbt.plugins.{ CorePlugin, IvyPlugin, JvmPlugin }
import sbt.util.LogExchange
import xsbti._

private[internal] object ClassLoaderWarmup {
  def warmup(): Unit = {
    if (Runtime.getRuntime.availableProcessors > 1) {
      val executorService: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors - 1)
      def submit[R](f: => R): Unit = {
        executorService.submit(new Runnable {
          override def run(): Unit = { f; () }
        })
        ()
      }

      submit(LogExchange.context)
      submit(Class.forName("sbt.internal.parser.SbtParserInit").getConstructor().newInstance())
      submit(CorePlugin.projectSettings)
      submit(IvyPlugin.projectSettings)
      submit(JvmPlugin.projectSettings)
      submit(() => {
        try {
          val clazz = Class.forName("scala.reflect.runtime.package$")
          clazz.getMethod("universe").invoke(clazz.getField("MODULE$").get(null))
        } catch {
          case _: Exception =>
        }
        executorService.shutdown()
      })
    }
  }
}

/**
 * Generates a new app configuration and invokes xMainImpl.run. For AppConfigurations generated
 * by recent launchers, it is unnecessary to modify the original configuration, but configurations
 * generated by older launchers need to be modified to place the test interface jar higher in
 * the class hierarchy. The methods this object are implemented without using the scala library
 * so that we can avoid loading any classes from the old scala provider. Verified as of
 * sbt 1.3.0 that there are no references to the scala standard library in any of the methods
 * in this file.
 */
private[sbt] class XMainConfiguration {
  def run(moduleName: String, configuration: xsbti.AppConfiguration): xsbti.MainResult = {
    val updatedConfiguration =
      if (configuration.provider.scalaProvider.launcher.topLoader.getClass.getCanonicalName
            .contains("TestInterfaceLoader")) {
        configuration
      } else {
        makeConfiguration(configuration)
      }
    val loader = updatedConfiguration.provider.loader
    Thread.currentThread.setContextClassLoader(loader)
    val clazz = loader.loadClass(s"sbt.$moduleName$$")
    val instance = clazz.getField("MODULE$").get(null)
    val runMethod = clazz.getMethod("run", classOf[xsbti.AppConfiguration])
    try {
      val clw = loader.loadClass("sbt.internal.ClassLoaderWarmup$")
      clw.getMethod("warmup").invoke(clw.getField("MODULE$").get(null))
      runMethod.invoke(instance, updatedConfiguration).asInstanceOf[xsbti.MainResult]
    } catch {
      case e: InvocationTargetException =>
        // This propogates xsbti.FullReload to the launcher
        throw e.getCause
    }
  }

  private def makeConfiguration(configuration: xsbti.AppConfiguration): xsbti.AppConfiguration = {
    val baseLoader = classOf[XMainConfiguration].getClassLoader
    val className = "sbt/internal/XMainConfiguration.class"
    val url = baseLoader.getResource(className)
    val path = url.toString.replaceAll(s"$className$$", "")
    val urlArray = new Array[URL](1)
    urlArray(0) = new URL(path)
    val topLoader = configuration.provider.scalaProvider.launcher.topLoader
    // This loader doesn't have the scala library in it so it's critical that none of the code
    // in this file use the scala library.
    val modifiedLoader = new XMainClassLoader(urlArray, topLoader)
    val xMainConfigurationClass = modifiedLoader.loadClass("sbt.internal.XMainConfiguration")
    val instance: AnyRef =
      xMainConfigurationClass.getConstructor().newInstance().asInstanceOf[AnyRef]

    val metaBuildLoaderClass = modifiedLoader.loadClass("sbt.internal.MetaBuildLoader")
    val method = metaBuildLoaderClass.getMethod("makeLoader", classOf[AppProvider])

    val loader = method.invoke(null, configuration.provider).asInstanceOf[ClassLoader]

    Thread.currentThread.setContextClassLoader(loader)
    val modifiedConfigurationClass =
      modifiedLoader.loadClass("sbt.internal.XMainConfiguration$ModifiedConfiguration")
    val cons = modifiedConfigurationClass.getConstructors()(0)
    close(configuration.provider.loader)
    val scalaProvider = configuration.provider.scalaProvider
    val providerClass = scalaProvider.getClass
    val _ = try {
      val method = providerClass.getMethod("loaderLibraryOnly")
      close(method.invoke(scalaProvider).asInstanceOf[ClassLoader])
      1
    } catch { case _: NoSuchMethodException => 1 }
    close(scalaProvider.loader)
    close(configuration.provider.loader)
    cons.newInstance(instance, configuration, loader).asInstanceOf[xsbti.AppConfiguration]
  }

  /*
   * Replaces the AppProvider.loader method with a new loader that puts the sbt test interface
   * jar ahead of the rest of the sbt classpath in the classloading hierarchy.
   */
  private[sbt] class ModifiedConfiguration(
      val configuration: xsbti.AppConfiguration,
      val metaLoader: ClassLoader
  ) extends xsbti.AppConfiguration {

    private class ModifiedAppProvider(val appProvider: AppProvider) extends AppProvider {
      private val delegate = configuration.provider.scalaProvider
      object ModifiedScalaProvider extends ScalaProvider {
        override def launcher(): Launcher = new Launcher {
          private val delegateLauncher = delegate.launcher
          private val interfaceLoader = metaLoader.loadClass("sbt.testing.Framework").getClassLoader
          override def getScala(version: String): ScalaProvider = getScala(version, "")
          override def getScala(version: String, reason: String): ScalaProvider =
            getScala(version, reason, "org.scala-lang")
          override def getScala(version: String, reason: String, scalaOrg: String): ScalaProvider =
            delegateLauncher.getScala(version, reason, scalaOrg)
          override def app(id: xsbti.ApplicationID, version: String): AppProvider =
            delegateLauncher.app(id, version)
          override def topLoader(): ClassLoader = interfaceLoader
          override def globalLock(): GlobalLock = delegateLauncher.globalLock()
          override def bootDirectory(): File = delegateLauncher.bootDirectory()
          override def ivyRepositories(): Array[xsbti.Repository] =
            delegateLauncher.ivyRepositories()
          override def appRepositories(): Array[xsbti.Repository] =
            delegateLauncher.appRepositories()
          override def isOverrideRepositories: Boolean = delegateLauncher.isOverrideRepositories
          override def ivyHome(): File = delegateLauncher.ivyHome()
          override def checksums(): Array[String] = delegateLauncher.checksums()
        }
        override def version(): String = delegate.version
        override def loader(): ClassLoader = metaLoader.getParent
        override def jars(): Array[File] = delegate.jars
        @deprecated("Implements deprecated api", "1.3.0")
        override def libraryJar(): File = delegate.libraryJar
        @deprecated("Implements deprecated api", "1.3.0")
        override def compilerJar(): File = delegate.compilerJar
        override def app(id: xsbti.ApplicationID): AppProvider = delegate.app(id)
        def loaderLibraryOnly(): ClassLoader = metaLoader.getParent.getParent
      }
      override def scalaProvider(): ModifiedScalaProvider.type = ModifiedScalaProvider
      override def id(): xsbti.ApplicationID = appProvider.id()
      override def loader(): ClassLoader = metaLoader
      @deprecated("Implements deprecated api", "1.3.0")
      override def mainClass(): Class[_ <: AppMain] = appProvider.mainClass()
      override def entryPoint(): Class[_] = appProvider.entryPoint()
      override def newMain(): AppMain = appProvider.newMain()
      override def mainClasspath(): Array[File] = appProvider.mainClasspath()
      override def components(): ComponentProvider = appProvider.components()
    }
    override def arguments(): Array[String] = configuration.arguments
    override def baseDirectory(): File = configuration.baseDirectory
    override def provider(): AppProvider = new ModifiedAppProvider(configuration.provider)
  }

}

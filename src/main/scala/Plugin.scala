package bintray

import bintry.{ BooleanAttr, Client, Licenses, VersionAttr }
import dispatch.as
import java.io.File
import sbt.{ Credentials, Global, Path, Resolver, Setting, Task }
import sbt.Def.{ Initialize, setting, task }
import sbt.Keys._
import sbt.Path.richFile
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration

object Plugin extends sbt.Plugin with DispatchHandlers {
  import bintray.Keys._

  object await {
    def result[T](f: => Future[T]) = Await.result(f, Duration.Inf)
    def ready[T](f: => Future[T]) = Await.ready(f, Duration.Inf)
  }

  // publishes version attributes after publishing artifact files
  private def publishWithVersionAttrs: Initialize[Task[Unit]] =
    (publish, publishVersionAttributes)(_ && _)

  /** updates a package version with the values defined in versionAttributes in bintray*/
  private def publishVersionAttributesTask: Initialize[Task[Unit]] =
    task {
      val tmp = ensureCredentials.value
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      val versionAttrs = (versionAttributes in bintray).value
      val pkg = (name in bintray).value
      val vers = version.value
      val BintrayCredentials(user, key) = tmp
      val bty = Client(user, key).repo(btyOrg.getOrElse(user), repo)
      val attributes = versionAttrs.toList
      await.ready(bty.get(pkg).version(vers).attrs.update(attributes:_*)())
    }

  /** Ensure user-specific bintray package exists. This will have a side effect of updating the packages attrs
   *  when it exists. Perhaps we want to factor that into an explicit task. */
  private def ensurePackageTask: Initialize[Task[Unit]] =
    task {
      val tmp = ensureCredentials.value
      val BintrayCredentials(user, key) = tmp
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      val pkg = (name in bintray).value
      val desc = (description in bintray).value
      val labels = (packageLabels in bintray).value
      val attrs = (packageAttributes in bintray).value
      val lics = licenses.value
      val bty = Client(user, key).repo(btyOrg.getOrElse(user), repo)
      val exists =
        if (await.result(bty.get(pkg)(asFound))) {
          // update existing attrs
          if (!attrs.isEmpty) await.ready(bty.get(pkg).attrs.update(attrs.toList:_*)())
          true
        } else {
          val created = await.result(
            bty.createPackage(pkg)
              .desc(desc)
              .licenses(lics.map(_._1):_*)
              .labels(labels:_*)(asCreated))
          // assign attrs
          if (created && !attrs.isEmpty) await.ready(
            bty.get(pkg).attrs.set(attrs.toList:_*)())
          created
        }
      if (!exists) sys.error(
        s"was not able to find or create a package for ${btyOrg.getOrElse(user)} in repo $repo named $name")
    }

  /** set a user-specific bintray publishTo endpoint */
  private def publishToBintray: Initialize[Option[Resolver]] =
    setting {
      val credsFile = (credentialsFile in bintray).value
      val creds = (bintrayCredentials in bintray).value
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      val pkg = (name in bintray).value
      val vers = version.value
      val mvnStyle = publishMavenStyle.value
      val isSbtPlugin = sbtPlugin.value
      ensuredCredentials(
        credsFile, creds.map(BintrayCredentials.api.toDirect(_)).toSeq, prompt = false).map {
        case BintrayCredentials(user, pass) =>
          val btyRepo = Client(user, pass).repo(btyOrg.getOrElse(user), repo)
          val btyPkg = btyRepo.get(pkg)
          if ("maven" == repo && !mvnStyle) println(
            "you have opted to publish to a repository named 'maven' but publishMavenStyle is assigned to false. This may result in unexpected behavior")
          Opts.resolver.publishTo(btyRepo, btyPkg, vers, mvnStyle, isSbtPlugin)
      }
    }

  /** unpublish (delete) a version of a package */
  private def unpublishTask: Initialize[Task[Unit]] =
    task {
      val tmp = ensureCredentials.value
      val BintrayCredentials(user, key) = tmp
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      val bty = Client(user, key).repo(btyOrg.getOrElse(user), repo)
      val pkg = (name in bintray).value
      val vers = version.value
      val log = streams.value.log
      await.result(
        bty.get(pkg).version(vers).delete(asStatusAndBody)) match {
          case (200, _) => log.info(s"$pkg@$vers was discarded")
          case (_, fail) =>sys.error(s"failed to discard $pkg@$vers: $fail")
        }
    }

  /** pgp sign remotely published artifacts then publish those signed artifacts */
  private def remoteSignTask: Initialize[Task[Unit]] =
    task {
      val log = streams.value.log
      val tmp = ensureCredentials.value
      val BintrayCredentials(user, key) = tmp
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      val bty = Client(user, key).repo(btyOrg.getOrElse(user), repo)
      val pkg = (name in bintray).value
      val vers = version.value
      val btyVersion = bty.get(pkg).version(vers)
      val cached = Cache.get("pgp.pass")
      val passphrase = cached.orElse(Prompt.descretely("Enter pgp passphrase")).getOrElse {
        sys.error("pgp passphrase is required")
      }
      val (status, body) = await.result(
        btyVersion.sign(passphrase)(asStatusAndBody))
      if (status == 200) {
        // only ask for pgp credentials once for a given sbt session
        Cache.put(("pgp.pass", passphrase))
        log.info(s"$pkg@$vers was signed")
        // after signing the remote artifacts, they remain
        // unpublished (not available for download)
        // we are opting to publish those unpublished
        // artifacts here
        val (pubStatus, pubBody) = await.result(
          btyVersion.publish(asStatusAndBody))
        if (pubStatus != 200) {
          sys.error(s"failed to publish signed artifacts: $pubBody")
        }
      }
      else sys.error(s"failed to sign $pkg@$vers: $body")
    }

  /** synchronize a published set of artifacts for a pkg version to mvn central
   *  this requires already having a sonatype oss account set up */
  private def syncMavenCentralTask: Initialize[Task[Unit]] =
    task {
      val log = streams.value.log
      val tmp = ensureCredentials.value
      val BintrayCredentials(user, key) = tmp
      val repo = (repository in bintray).value
      val btyOrg = (bintrayOrganization in bintray).value
      val bty = Client(user, key).repo(btyOrg.getOrElse(user), repo)
      val pkg = (name in bintray).value
      val vers = version.value
      val creds = credentials.value
      val btyVersion = bty.get(pkg).version(vers)
      val BintrayCredentials(sonauser, sonapass) =
        resolveSonatypeCredentials(creds)
      await.result(
        btyVersion.mavenCentralSync(sonauser, sonapass)(asStatusAndBody)) match {
        case (200, body) =>
          // store these sonatype credentials in memory for the remainder of the sbt session
          Cache.putMulti(
            ("sonauser", sonapass), ("sonapass", sonapass))
          log.info(s"$pkg@$vers was synced with maven central")
          log.info(s"body $body")
        case (404, body) =>
          log.info(s"$pkg@$vers was not found. try publishing this package version to bintray first by typing `publish`")
          log.info(s"body $body")
        case (_, body) =>
          // ensure these items are removed from the cache, they are probably bad
          Cache.removeMulti("sona.user", "sona.pass")
          sys.error(s"failed to sync $pkg@$vers with maven central: $body")
        }
    }

  /** if credentials exist, append a user-specific resolver */
  private def appendBintrayResolver: Initialize[Seq[Resolver]] =
    setting {
      val creds = (credentialsFile in bintray).value
      val btyOrg = (bintrayOrganization in bintray).value
      val repo = (repository in bintray).value
      BintrayCredentials.read(creds).fold({ err =>
        println(s"bintray credentials $err is malformed")
        Nil
      }, {
        _.map { case BintrayCredentials(user, _) => Seq(Opts.resolver.repo(btyOrg.getOrElse(user), repo)) }
         .getOrElse(Nil)
      })
    }

  /** Lists versions of bintray packages corresponding to the current project */
  private def packageVersionsTask: Initialize[Task[Seq[String]]] =
    task {
      val log = streams.value.log
      val credsFile = (credentialsFile in bintray).value
      val creds = (bintrayCredentials in bintray).value      
      val repo = (repository in bintray).value
      val pkgName = (name in bintray).value
      ensuredCredentials(
        credsFile, creds.map(BintrayCredentials.api.toDirect).toSeq, prompt = true).map {
        case BintrayCredentials(user, pass) =>
          import org.json4s._
          val pkg = Client(user, pass).repo(user, repo).get(pkgName)
          log.info(s"fetching package versions for package $pkgName")
          await.result(pkg(EitherHttp({ _ => JNothing}, as.json4s.Json))).fold({ js =>
            log.warn("package does not exist")
            Nil
          }, { js =>
            for {
              JObject(fs) <- js
              ("versions", JArray(versions)) <- fs
              JString(versionString) <- versions
            } yield {
              log.info(s"- $versionString")
              versionString
            }
          })
      }.getOrElse(Nil)
    }

  /** assign credentials or ask for new ones */
  private def changeCredentialsTask: Initialize[Task[Unit]] =
    (credentialsFile in bintray).map {
      credsFile =>
        BintrayCredentials.read(credsFile).fold(sys.error(_), _ match {
          case None =>
            saveBintrayCredentials(credsFile)(requestCredentials())
          case Some(BintrayCredentials(user, pass)) =>
            saveBintrayCredentials(credsFile)(requestCredentials(Some(user), Some(pass)))
        })
    }

  private def whoamiTask: Initialize[Task[String]] =
    task {
      val log = streams.value.log
      val creds = (credentialsFile in bintray).value
      val is = BintrayCredentials.read(creds).fold(sys.error(_), _ match {
        case None =>
          "nobody"
        case Some(BintrayCredentials(user, _)) =>
          user
      })
      log.info(is)
      is
    }

  private def ensureCredentialsTask: Initialize[Task[BintrayCredentials]] =
    task {
      ensuredCredentials(
        (credentialsFile in bintray).value,
        (bintrayCredentials in bintray).value.map(BintrayCredentials.api.toDirect).toSeq ++ credentials.value).get
    }

  private def resolveSonatypeCredentials(
    creds: Seq[sbt.Credentials]): BintrayCredentials =
    Credentials.forHost(creds, BintrayCredentials.sonatype.Host)
      .map { d => (d.userName, d.passwd) }
      .getOrElse(requestSonatypeCredentials) match {
        case (user, pass) => BintrayCredentials(user, pass)
      }

  /** publishing to bintray requires you must have defined a license they support */
  private def ensureLicensesTask: Initialize[Task[Unit]] =
    task {
      val ls = licenses.value
      val acceptable = Licenses.Names.mkString(", ")
      if (ls.isEmpty) sys.error(
        s"you must define at least one license for this project. Please choose one or more of $acceptable")
      if (!ls.forall { case (name, _) => Licenses.Names.contains(name) }) sys.error(
        s"One or more of the defined licenses where not among the following allowed licenses $acceptable")
    }

  private def requestSonatypeCredentials: (String, String) = {
    val cached = Cache.getMulti("sona.user", "sona.pass")
    (cached("sona.user"), cached("sona.pass")) match {
      case (Some(user), Some(pass)) =>
        (user, pass)
      case _ =>
        val name = Prompt("Enter sonatype username").getOrElse {
          sys.error("sonatype username required")
        }
        val pass = Prompt.descretely("Enter sonatype password").getOrElse {
          sys.error("sonatype password is required")
        }
        (name, pass)
    }
  }

  // todo: generalize this for both bintray & sonatype credential prompts
  private def requestCredentials(
    defaultName: Option[String] = None,
    defaultKey: Option[String] = None): (String, String) = {
    val name = Prompt("Enter bintray username%s" format(
      defaultName.map(" (%s)".format(_)).getOrElse(""))).orElse(defaultName).getOrElse {
      sys.error("bintray username required")
    }
    val pass = Prompt.descretely("Enter bintray API key %s" format(
      defaultKey.map(_ => "(use current)").getOrElse("(under https://bintray.com/profile/edit)"))).orElse(defaultKey).getOrElse {
      sys.error("bintray API key required")
    }
    (name, pass)
  }

  private def saveBintrayCredentials(to: File)(creds: (String, String)) = {
    println(s"saving credentials to $to")
    val (name, pass) = creds
    BintrayCredentials.writeBintray(name, pass, to)
    println("reload project for sbt setting `publishTo` to take effect")
  }

  /** tries to resolve bintray credentials from sbt's credentials key, then bintray's credentialsFile.
   *  if prompt is true, we enter an interactive mode to collect them from the user */
  private def ensuredCredentials(
    credsFile: File, creds: Seq[sbt.Credentials], prompt: Boolean = true): Option[BintrayCredentials] =
    Credentials.forHost(creds, BintrayCredentials.api.Host)
      .map(Credentials.toDirect).map { dc =>
        val resolved = BintrayCredentials(dc.userName, dc.passwd)
        // side effect! wee oo wee oo - let's write these credentials to the standard credsPath
        if (!credsFile.exists) {
          BintrayCredentials.writeBintray(dc.userName, dc.passwd, credsFile)
        }
        resolved
      }.orElse {
        BintrayCredentials.read(credsFile).fold(sys.error(_), _ match {
          case None =>
            if (prompt) {
              println("bintray-sbt requires your bintray credentials.")
              saveBintrayCredentials(credsFile)(requestCredentials())
              ensuredCredentials(credsFile, creds, prompt)
            } else {
              println(s"Missing bintray credentials $credsFile. Some bintray features depend on this.")
              None
            }
          case creds => creds
        })
      }

  def bintrayPublishSettings: Seq[Setting[_]] = Seq(
    credentialsFile in bintray in Global := Path.userHome / ".bintray" / ".credentials",
    name in bintray := moduleName.value,
    bintrayOrganization in bintray in Global := { if (sbtPlugin.value) Some("sbt") else None },
    repository in bintray in Global := { if (sbtPlugin.value) "sbt-plugin-releases" else "maven" },
    publishMavenStyle := {
      if (sbtPlugin.value) false else publishMavenStyle.value
    },
    packageLabels in bintray := Nil,
    description in bintray <<= description,
    // inlineable credentials
    bintrayCredentials in bintray := None,
    // note: publishTo may not have dependencies. therefore, we can not rely well on inline overrides
    // for inline credentials resolution we recommend defining bintrayCredentials _before_ mixing in the defaults
    // perhaps we should try overriding something in the publishConfig setting -- https://github.com/sbt/sbt-pgp/blob/master/pgp-plugin/src/main/scala/com/typesafe/sbt/pgp/PgpSettings.scala#L124-L131
    publishTo in bintray <<= publishToBintray,
    resolvers in bintray <<= appendBintrayResolver,
    credentials in bintray <<= (credentialsFile in bintray, bintrayCredentials in bintray).map {
      Credentials(_) +: _.map(BintrayCredentials.api.toDirect).toSeq
    },
    packageAttributes in bintray <<= (sbtPlugin, sbtVersion) {
      (plugin, sbtVersion) =>
        if (plugin) Map(AttrNames.sbtPlugin -> Seq(BooleanAttr(plugin)))
        else Map.empty
    },
    versionAttributes in bintray <<= (crossScalaVersions, sbtPlugin, sbtVersion) {
      (scalaVersions, plugin, sbtVersion) =>
        val sv = Map(AttrNames.scalas -> scalaVersions.map(VersionAttr(_)))
        if (plugin) sv ++ Map(AttrNames.sbtVersion-> Seq(VersionAttr(sbtVersion))) else sv
    },
    ensureLicenses <<= ensureLicensesTask,
    ensureCredentials <<= ensureCredentialsTask,
    ensureBintrayPackageExists <<= ensurePackageTask,
    publishVersionAttributes <<= publishVersionAttributesTask,
    unpublish in bintray <<= unpublishTask.dependsOn(ensureBintrayPackageExists, ensureLicenses),
    remoteSign in bintray <<= remoteSignTask,
    syncMavenCentral in bintray <<= syncMavenCentralTask
  ) ++ Seq(
    resolvers <++= resolvers in bintray,
    credentials <++= credentials in bintray,
    publishTo <<= publishTo in bintray,
    // We attach this to publish configruation, so that publish-signed in pgp plugin can work.
    publishConfiguration <<= publishConfiguration.dependsOn(ensureBintrayPackageExists, ensureLicenses), 
    publish <<= publishWithVersionAttrs
  )

  def bintrayCommonSettings: Seq[Setting[_]] = Seq(
    changeCredentials in bintray <<= changeCredentialsTask,
    whoami in bintray <<= whoamiTask
  )

  def bintrayQuerySettings: Seq[Setting[_]] = Seq(
    packageVersions in bintray <<= packageVersionsTask
  )

  def bintrayResolverSettings: Seq[Setting[_]] = Seq(
    resolvers += Opts.resolver.jcenter
  )

  def bintraySettings: Seq[Setting[_]] =
    bintrayCommonSettings ++ bintrayResolverSettings ++ bintrayPublishSettings ++ bintrayQuerySettings
}
package net.kindleit.play2.natpackplugin

import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager._
import com.typesafe.sbt.packager.Keys._

object NatPackPlugin extends Plugin with debian.DebianPlugin {

  object NatPackKeys extends linux.Keys with debian.DebianKeys {
    lazy val debian        = TaskKey[File]("deb", "Create the debian package")
    lazy val debianPreInst = TaskKey[File]("debian-preinst-file", "Debian pre install maintainer script")
    lazy val debianPreRm   = TaskKey[File]("debian-prerm-file",   "Debian pre remove maintainer script")
    lazy val debianPostRm  = TaskKey[File]("debian-postrm-file",  "Debian post remove maintainer script")
    lazy val userName      = SettingKey[String]("Unix user to own the extracted package files")
    lazy val groupName     = SettingKey[String]("Unix group to own the extracted package files")
  }
  private val npkg = NatPackKeys

  import NatPackKeys._

  lazy val natPackSettings: Seq[Project.Setting[_]] = linuxSettings ++ debianSettings ++ Seq(

    //evaluate and set defaults
    name               in Debian <<= normalizedName,
    version            in Debian <<= version,
    maintainer         in Debian <<= maintainer,
    userName           in Debian <<= userName,
    groupName          in Debian <<= groupName,
    description        in Debian <<= description,
    packageSummary     <<= description,
    packageSummary     in Debian <<= packageSummary,
    packageDescription <<= description,
    packageDescription in Debian <<= packageDescription,

    linuxPackageMappings <++=
      (baseDirectory, target, normalizedName, npkg.userName, npkg.groupName, packageSummary in Debian,
       PlayProject.playPackageEverything, dependencyClasspath in Runtime) map {
      (root, target, name, usr, grp, desc, pkgs, deps) ⇒
        val start = target / "start"
        val init  = target / "initFile"

        IO.write(start, startFileContent)
        IO.write(init,  initFilecontent(name, desc))

        val jarLibs = (pkgs ++ deps.map(_.data)) filter(_.ext == "jar") map { jar ⇒
          packageMapping(jar -> "/var/lib/%s/lib/%s".format(name, jar.getName)) withUser(usr) withGroup(grp) withPerms("0644")
        }

        val appConf = config map { cfg ⇒
          packageMapping(root / cfg -> "/var/lib/%s/application.conf".format(name)) withUser(usr) withGroup(grp) withPerms("0644")
        }

        val confFiles = Seq(
          packageMapping(start -> "/var/lib/%s/start".format(name)) withUser(usr) withGroup(grp),
          packageMapping(init -> "/etc/init.d/%s".format(name)) withPerms("0754") withConfig(),
          packageMapping(root / "README" -> "/var/lib/%s/README".format(name)) withUser(usr) withGroup(grp) withPerms("0644")
        )

        val otherPkgs = pkgs filter(_.ext != "jar") map { pkg ⇒
          packageMapping(pkg -> "/var/lib/%s/%s".format(name, pkg.getName)) withUser(usr) withGroup(grp)
        }

        jarLibs ++ appConf ++ confFiles ++ otherPkgs
    },
    npkg.debian <<= (packageBin in Debian, streams) map { (deb, s) ⇒
      s.log.info("Package %s ready".format(deb))
      s.log.info("If you wish to sign the package as well, run %s:%s".format(Debian, debianSign.key))
      deb
    }
  ) ++ inConfig(Debian)( Seq(
    npkg.debianPreInst        <<= (target, normalizedName, userName, groupName) map debFile3("postinst", postInstContent),
    npkg.debianPreRm          <<= (target, normalizedName) map debFile1("prerm", preRmContent),
    npkg.debianPostRm         <<= (target, normalizedName, userName) map debFile2("postrm", postRmContent),
    debianExplodedPackage     <<= debianExplodedPackage.dependsOn(npkg.debianPreInst, npkg.debianPreRm, npkg.debianPostRm),
    debianPackageDependencies ++= Seq("java2-runtime", "daemon"),
    debianPackageRecommends    += "git"

  )) ++
  SettingsHelper.makeDeploymentSettings(Debian, packageBin in Debian, "deb")

}

package algimk

import org.specs2.mutable.Spec

class FileSystemSpec extends Spec {
  "FileSystem" should {
    "transform url to valid fileName to save as .html" in {
      FileSystem.urlAsFile("http://www.google.com/something/else") must
        beSome("http_www_google_com_something_else.html")
    }
  }

}

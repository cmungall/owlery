package org.phenoscape.owlery

import org.apache.jena.query.Query
import org.apache.jena.system.JenaSystem
import org.phenoscape.owlery.Owlery.OwleryMarshaller
import org.phenoscape.owlery.SPARQLFormats._
import org.phenoscape.owlet.ManchesterSyntaxClassExpressionParser
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.OWLClassExpression
import org.semanticweb.owlapi.reasoner.InferenceType

import com.typesafe.config.ConfigFactory

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.server.HttpApp
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import spray.json._
import spray.json.DefaultJsonProtocol._

object Main extends HttpApp with App {

  JenaSystem.init()

  val factory = OWLManager.getOWLDataFactory

  implicit val IRIUnmarshaller: Unmarshaller[String, IRI] = Unmarshaller.strict(IRI.create)

  implicit val SimpleMapFromJSONString: Unmarshaller[String, Map[String, String]] = Unmarshaller.strict { text =>
    text.parseJson match {
      case o: JsObject => o.fields.map {
        case (key, JsString(value)) => key -> value
        case _                      => throw new IllegalArgumentException(s"Only string values are supported in JSON map: $text")
      }
      case _ => throw new IllegalArgumentException(s"Not a valid JSON map: $text")
    }
  }

  val NullQuery = new Query()

  case class PrefixedManchesterClassExpression(text: String, prefixes: Map[String, String]) {

    val parseResult = ManchesterSyntaxClassExpressionParser.parse(text, prefixes)
    require(parseResult.isSuccess, parseResult.swap.getOrElse("Error parsing class expression"))
    val expression = parseResult.toOption.get

  }

  case class PrefixedIndividualIRI(text: String, prefixes: Map[String, String]) {

    val parseResult = ManchesterSyntaxClassExpressionParser.parseIRI(text, prefixes)
    require(parseResult.isSuccess, parseResult.swap.getOrElse("Error parsing individual IRI"))
    val iri = parseResult.toOption.get

  }

  val NoPrefixes = Map.empty[String, String]

  def objectAndPrefixParametersToClass(subroute: OWLClassExpression => (Route)): Route =
    parameters('object, 'prefixes.as[Map[String, String]].?(NoPrefixes)).as(PrefixedManchesterClassExpression) { ce =>
      subroute(ce.expression)
    }

  def initializeReasoners() = Owlery.kbs.values.foreach(_.reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY))

  initializeReasoners()

  val conf = ConfigFactory.load()
  val port = conf.getInt("owlery.port")
  val host = conf.getString("owlery.host")

  def routes: Route = cors() {
    pathPrefix("kbs") {
      pathPrefix(Segment) { kbName =>
        Owlery.kb(kbName) match {
          case None => reject
          case Some(kb) => {
            path("subclasses") {
              objectAndPrefixParametersToClass { expression =>
                parameters('direct.?(false), 'includeEquivalent.?(false), 'includeNothing.?(false), 'includeDeprecated.?(true)) { (direct, includeEquivalent, includeNothing, includeDeprecated) =>
                  complete {
                    kb.querySubClasses(expression, direct, includeEquivalent, includeNothing, includeDeprecated)
                  }
                }
              }
            } ~
              path("superclasses") {
                objectAndPrefixParametersToClass { expression =>
                  parameters('direct.?(false), 'includeEquivalent.?(false), 'includeThing.?(false), 'includeDeprecated.?(true)) { (direct, includeEquivalent, includeThing, includeDeprecated) =>
                    complete {
                      kb.querySuperClasses(expression, direct, includeEquivalent, includeThing, includeDeprecated)
                    }
                  }
                }
              } ~
              path("instances") {
                objectAndPrefixParametersToClass { expression =>
                  parameters('direct.?(false), 'includeDeprecated.?(true)) { (direct, includeDeprecated) =>
                    complete {
                      kb.queryInstances(expression, direct, includeDeprecated)
                    }
                  }
                }
              } ~
              path("equivalent") {
                objectAndPrefixParametersToClass { expression =>
                  parameters('includeDeprecated.?(true)) { includeDeprecated =>
                    complete {
                      kb.queryEquivalentClasses(expression, includeDeprecated)
                    }
                  }
                }
              } ~
              path("satisfiable") {
                objectAndPrefixParametersToClass { expression =>
                  complete {
                    kb.isSatisfiable(expression)
                  }
                }
              } ~
              path("types") {
                parameters('object, 'prefixes.as[Map[String, String]].?(NoPrefixes)).as(PrefixedIndividualIRI) { preIRI =>
                  parameters('direct.?(true), 'includeThing.?(false), 'includeDeprecated.?(true)) { (direct, includeThing, includeDeprecated) =>
                    complete {
                      kb.queryTypes(factory.getOWLNamedIndividual(preIRI.iri), direct, includeThing, includeDeprecated)
                    }
                  }
                }
              } ~
              path("sparql") {
                get {
                  parameter('query.as[Query]) { query =>
                    complete {
                      kb.performSPARQLQuery(query)
                    }
                  }
                } ~
                  post {
                    parameter('query.as[Query].?(NullQuery)) { query =>
                      query match {
                        case NullQuery => handleWith(kb.performSPARQLQuery)
                        case _ => complete {
                          kb.performSPARQLQuery(query)
                        }
                      }
                    }
                  }
              } ~
              path("expand") {
                get {
                  parameter('query.as[Query]) { query =>
                    complete {
                      kb.expandSPARQLQuery(query)
                    }
                  }
                } ~
                  post {
                    handleWith(kb.expandSPARQLQuery)
                  }
              } ~
              pathEnd {
                complete {
                  kb.summary
                }
              }
          }
        }
      } ~
        pathEnd {
          complete {
            Owlery
          }
        }
    }
  }

  // Starting the server
  Main.startServer(host, port)

}
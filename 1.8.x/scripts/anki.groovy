// @ExecutionModes({ON_SINGLE_NODE})


import groovy.transform.ToString
import org.freeplane.api.ConnectorRO
import org.freeplane.api.ExternalObject
import org.freeplane.api.MindMapRO
import org.freeplane.api.Node
import org.freeplane.api.NodeRO

import java.nio.charset.Charset


@ToString
class StatefulContext {
  StatefulCategory category
  Node node
  List<Card> cards

  String getText() {
    node?.text
  }
}

interface Visitor {
  void visit(StatefulContext context)
}

class Visitors {
  static final Map<NodeType, Visitor> BEFORE_NODE = [
          (NodeType.SUBJECT): Visitors.&visitSubject as Visitor,
          (NodeType.TOPIC): Visitors.&visitTopic as Visitor,
          (NodeType.SUBTOPIC): Visitors.&visitSubtopic as Visitor,
          (NodeType.CONCEPT): Visitors.&visitConcept as Visitor,
          (NodeType.QUESTION): Visitors.&visitQuestion as Visitor,
          (NodeType.ANSWER): Visitors.&visitAnswer as Visitor
  ]

  static final Map<NodeType, Visitor> AFTER_NODE = [
          (NodeType.SUBJECT): Visitors.&unvisitSubject as Visitor,
          (NodeType.TOPIC): Visitors.&unvisitTopic as Visitor,
          (NodeType.SUBTOPIC): Visitors.&unvisitSubtopic as Visitor,
          (NodeType.CONCEPT): Visitors.&unvisitConcept as Visitor
  ]

  static void visitSubject(StatefulContext context) {
    context.category.subject = context.text
  }

  static void unvisitSubject(StatefulContext context) {
    context.category.subject = null
  }

  static void visitTopic(StatefulContext context) {
    context.category.topic = context.text
  }

  static void unvisitTopic(StatefulContext context) {
    context.category.topic = null
  }

  static void visitSubtopic(StatefulContext context) {
    context.category.subtopics.add(context.text)
  }

  static void unvisitSubtopic(StatefulContext context) {
    context.category.subtopics.removeLast()
  }

  static void visitConcept(StatefulContext context) {
    context.category.concepts.add(context.text)
  }

  static void unvisitConcept(StatefulContext context) {
    context.category.concepts.removeLast()
  }

  static void visitQuestion(StatefulContext context) {
    Card template = new Card()
      
    String questionDetails = context.node.details?.plain
    String questionText = questionDetails
    if (questionDetails == null || questionDetails.isBlank()) {
      questionText = context.node.plainText
    }
    template.questionStructure.question = evaluateExpression(questionText, context.node)
    template.questionStructure.questionAsAssociation = evaluateExpression(context.node.plainText, context.node)

    populateBreadcrumbs(context, template)
      
    if (context.node.children.any { NodeRO it -> Utilities.getType(it) == NodeType.HINT && it.children.size() > 0} ) {
      createHintedQuestions(context, template)
    } else {
      createSimpleQuestion(context, template)
    }
  }


  static void visitAnswer(StatefulContext context) {
    context.node.connectorsOut.findAll { ConnectorRO c -> c.target == context.node.parent && c.middleLabel != null && c.middleLabel != '' }
      .each { ConnectorRO c ->
        visitReverseQuestion(context, c)
      }
  }

  private static void visitReverseQuestion(StatefulContext context, ConnectorRO c) {
    println("    >> visiting rev question")
    Card card = new Card()

    populateBreadcrumbs(context, card)
    updateRevUuids(card, context.node)

    card.questionStructure.question = evaluateExpression(c.middleLabel, context.node)

    AnswerStructure ans = new AnswerStructure()
    ans.primary = true
    ans.information = evaluateExpression(context.node.parent.parent.text, context.node.parent.parent)
    card.questionStructure.answers = [ans]

//    context.cards.push(card)
//    println "Pushed card $card"
  }

  static void createSimpleQuestion(StatefulContext context, Card template) {
    Card card = template.createFromTemplate()
    updateUuids(card, context.node)

    card.questionStructure.answers =  context.node.children.collect { Node node ->
      AnswerStructure answer = new AnswerStructure()
      answer.primary = true
      answer.information = appendImage(
              evaluateExpression(node.text, node),
              node
      )
      answer.supplementaryNotes = node.noteText
      answer
    }
    context.cards.add(card)
    println "Pushed card $card"
  }

  private static void populateBreadcrumbs(StatefulContext context, Card template) {
    def cat = context.category
    template.subjectBreadcrumb = [cat.subject, cat.topic, *cat.subtopics]
    template.conceptBreadcrumb = [*cat.concepts]
  }

  static String appendImage(String base, Node node) {
    if (!node.externalObject) {
      return base
    }

    ExternalObject imgObj = node.externalObject
    String image = URLDecoder.decode(imgObj.uri, Charset.defaultCharset())
    image = image.substring(Math.max(0, image.indexOf("/") + 1))
    return """$base 
      </br>
       <img src="$image" style="max-width: 100%"/>
    """
  }

  static void updateUuids(Card card, Node node) {
    def oldUuidAttr = node['ankiUuid']?.text
    if (oldUuidAttr) {
      card.uuid = oldUuidAttr
    } else {
      node['ankiUuid'] = card.uuid
    }
  }

  static void updateRevUuids(Card card, Node node) {
    def oldUuidAttr = node['ankiRevUuid']?.text
    if (oldUuidAttr) {
      card.uuid = oldUuidAttr
    } else {
      def uuid = UUID.randomUUID().toString()
      node['ankiRevUuid'] = uuid
      card.uuid = uuid
    }
  }

  static void createHintedQuestions(StatefulContext context, Card template) {
    List<AnswerStructure> answers = context.node.children.collect { Node hint ->
      AnswerStructure ans = new AnswerStructure()
      ans.hint = hint.text
      ans.information = hint.children.collect { Node ansPiece ->
        appendImage(ansPiece.text, ansPiece)
      }.join("\n <br/> \n")

      // Workaround
      if (hint.children.size() > 0)
        ans.supplementaryNotes = hint.children.first().note

      ans
    }

    context.node['ankiUuid'] = null
    answers.eachWithIndex { question, idx ->
      Card newCard = template.createFromTemplate()
      newCard.questionStructure.answers = [*answers]
      newCard.questionStructure.answers.each { it.primary = false }
      newCard.questionStructure.answers[idx].primary = true
      updateUuids(newCard, context.node.children[idx])
      context.cards.add(newCard)
      println "Pushed hinted card $newCard"
    }
  }

  static String evaluateExpression(String text, Node node) {
    if (node['expr'] == 'false') {
      return text
    }

    if (text.contains("***")) {
      def pivot = node.parent.parent.parent
      text = text.replaceAll("\\*\\*\\*", evaluateExpression(pivot.plainText, pivot))
    }

    if (text.contains("**")) {
      def pivot = node.parent.parent
      text = text.replaceAll("\\*\\*", evaluateExpression(pivot.plainText, pivot))
    }

    if (text.contains("*")) {
      def pivot = node.parent
      text = text.replaceAll("\\*", evaluateExpression(pivot.plainText, pivot))
    }

    if (text.contains("^")) {
      text = text.replaceAll("\\^", node.plainText)
    }

    text
  }
}


class Card {
  String uuid
  List<String> tags = []
  QuestionStructure questionStructure = new QuestionStructure()
  String renderedQuestion
  String renderedAnswer
  List<String> subjectBreadcrumb = []
  List<String> conceptBreadcrumb = []
  
  Card createFromTemplate() {
    return new Card(tags: this.tags,
            questionStructure: this.questionStructure.createFromTemplate(),
            subjectBreadcrumb: [*subjectBreadcrumb], conceptBreadcrumb: [*conceptBreadcrumb],
          uuid: UUID.randomUUID(), )
  }

  @Override
  String toString() {
    "Card{questionStructure{$questionStructure}, subjectBC=$subjectBreadcrumb, " +
            "conceptBC=$conceptBreadcrumb, renderedQuestion={$renderedQuestion}, renderedAnswer={$renderedAnswer}}"
  }
}

class QuestionStructure {
  String question
  String questionAsAssociation
  List<AnswerStructure> answers = []

  QuestionStructure createFromTemplate() {
    return new QuestionStructure(question: this.question,
      questionAsAssociation: this.questionAsAssociation,
      answers: []
    )
  }

  @Override
  String toString() {
    "QS { question=<{$question}>, questionAsAssociation={$questionAsAssociation}, answers=$answers }"
  }
}

class AnswerStructure {
  String information
  String hint
  String supplementaryNotes
  boolean primary

  @Override
  String toString() {
    "AS { information=<{$information}>, hint=$hint, [[$primary]], suppl=${supplementaryNotes != null}"
  }
}

enum NodeType {
  SUBJECT, TOPIC, SUBTOPIC, CONCEPT, QUESTION, HINT, ANSWER, OTHER
}


class Utilities {
  static NodeType getType(NodeRO node) {
    def style = node.style.name
    def nodeStyleBasedOnType = NodeType.OTHER
    try {
      nodeStyleBasedOnType = NodeType.valueOf(style.toUpperCase())
    } catch (IllegalStateException) { }

    return nodeStyleBasedOnType
  }

  static void recreateFile(File file) {
    if (file.exists()) {
      file.delete()
    } else {
      file.createNewFile()
    }
  }
}


@ToString
class StatefulCategory {
  String subject
  String topic
  List<String> subtopics = []
  List<String> concepts = []
}

class CardRenderer {
  private static String css() {
    """
  <style type="text/css">
      * {
        box-sizing: border-box;
  /*         border: 1px dotted grey; */
        padding: 0px;
        margin: 0px;
      }
      
     .question-holder {
        position: relative;
        margin-left: auto;
        margin-right: auto;
        margin-top: 16px;
        width: 88%;
        max-width: 500px;
        min-height: 400px;
        border: 1px solid grey;
        border-radius: 4px;
    }
    
    .breadcrumb-panel {
        padding: 4px 16px;
        width: 100%;
        min-height: 40px;
        font-size: 10pt;
        border-bottom: 1px solid silver;
    }
    
    .breadcrumb-visible {
        display: inline-block;
    }
    
    .breadcrumb-hidden {
        background-color: rgba(230, 230, 230, 0.5);
        filter: blur(3px);
        display: inline-block;
    }
    
    .breadcrumb-hidden:hover {
      background-color: unset;
      filter: none;
    }
    
    .show-on-hover {
        background-color: rgba(230, 230, 230, 0.5);
        filter: blur(3px);
    }
    
    .show-on-hover:hover {
      background-color: unset;
      filter: none;
    }
    
    .blur {
        background-color: rgba(230, 230, 230, 0.5);
        filter: blur(3px);
    }
    
    .breadcrumb-hidden > .breadcrumb-element {
        z-index: -1;
    }
    
    .breadcrumb-element {
        display: inline-block;
    }
    
    .breadcrumb-element:not(:last-child)::after {
        content: "-";
        display: inline-block;
        margin-right: 3px;
        margin-left: 6px;
    }
    
    .breadcrumb-concept {
        display: inline;
        margin-left: 24px;
    }
    
    .question {
        margin-top: 30px;
        margin-bottom: 30px;
        padding-right: 16px;
        padding-left: 16px;
        font-size: 16pt;
    }
    
    .delimiter {
        box-sizing: content-box;
        border-top: 1px solid silver;
        margin-right: 8px;
        margin-left: 8px;
        max-width: 100%;
    }
    
    .answer-structure {
        margin-top: 30px;
        padding-left: 16px;
        padding-right: 16px;
    }
    
    .primary-answer-placeholder, .secondary-answer-placeholder {
        margin-bottom: 16px;
        padding: 4px;
    }
    
    .primary-answer-placeholder {
        background-color: #eefd3d;
    }
    
    .hint {
      display: inline-block;
      min-width: 10%;
    }
    
    .information {
      display: inline-block;
      min-width: 89%;
    }
    
    .supplementary-notes {
      padding-top: 20px;
      padding-left: 14px;
      font-size: 85%;
      color: #4c4a4a;
    }
    
    .uuid {
      position: absolute;
      bottom: 0px;
      right: 0px;
      color: lightgray;
      font-size: 10pt;
    }
    
  </style>
  """

  }

  private static String breadcrumbs(Card card) {

    """
        <div class="breadcrumb-panel">
            <div class="breadcrumb-visible breadcrumb-subject">
                ${card.subjectBreadcrumb.collect{
      "<div class=\"breadcrumb-element\">$it</div>"
    }.join("\n")}
            </div>
                
            <div class="breadcrumb-hidden breadcrumb-concept">
                ${card.conceptBreadcrumb.collect {
      "<div class=\"breadcrumb-element \">$it</div>"
    }.join("\n")} 
            </div>
        </div>
    """
  }

  private static String answerPiece(AnswerStructure it, boolean showOnHover, boolean blurAnswer) {
    """
        ${it.hint ? "<div class=\"hint\">${it.hint}</div>" : ""}

        <div class="information ${showOnHover ? 'show-on-hover' : ''} ${blurAnswer ? 'blur' : ''}">
              ${it.information}
        </div>
    """
  }

  private static String answerRevealed(AnswerStructure it) {
    """
        <div>
              ${it.hint ? "<div class=\"hint\">${it.hint}</div>" : ""}

              <div class="information">
                    ${it.information}
              </div>
        </div>

        ${it.supplementaryNotes == null ? '' : """
          <div class="supplementary-notes">
              ${it.supplementaryNotes}  
          </div>
        """}
    """
  }

  static void render(Card card) {
    card.renderedQuestion = """
      ${css()}
      <div class="question-holder">
            ${breadcrumbs(card)}

           <div class="question">
              ${card.questionStructure.question}
           </div>

          <div class="delimiter"></div>

          <div class="answer-structure">
              ${card.questionStructure.answers.collect {
      if (it.primary) {
        """
                    <div class="primary-answer-placeholder">
                      ${answerPiece(it, false, true)}
                     </div>
                  """
      } else {
        """
                    <div class="secondary-answer-placeholder">
                      ${answerPiece(it, true, false)}
                    </div>
                  """
      }
    }.join("\n")}
          </div>

          <div class="uuid">${card.uuid}</div>
      </div>
"""


    card.renderedAnswer =  """
      ${'' /**css()**/}
      <div class="question-holder">
             ${breadcrumbs(card)}

             <div class="question">
                ${card.questionStructure.question}
             </div>

            <div class="delimiter"/>

            <div class="answer-structure">
                ${card.questionStructure.answers.collect {
      if (it.primary) {
        """
                      <div class="primary-answer-placeholder">
                        ${answerRevealed(it)}
                       </div>
                    """
      } else {
        """
                      <div class="secondary-answer-placeholder">
                        ${answerRevealed(it)}
                      </div>
                    """
      }
    }.join("\n")}
            </div>

          <div class="uuid">${card.uuid}</div>
      </div>
     
"""
  }
}

class HtmlExporter {
  String dir

  void export(List<Card> cards) {
    cards.eachWithIndex { it, idx ->
      println "Rendering ${it.uuid}"
      CardRenderer.render(it)
      String desc = it.questionStructure.question.replaceAll(" ", "_").replaceAll("/", "_").replaceAll("\n", "")
      String filename = "$dir/${"${it.uuid}_${desc}_${idx}.html"}"
      println filename
      File file = new File(filename)
      Utilities.recreateFile(file)
      file.write(it.renderedQuestion + "<hr/>" + it.renderedAnswer)
    }
  }
}

class CsvExporter {
  String dir

  void export(List<Card> cards) {
    File file = new File("$dir/anki.txt")
    Utilities.recreateFile(file)
    cards.each {
      def columns = [it.uuid, it.subjectBreadcrumb.join(", "), it.conceptBreadcrumb.join(", "),
                     it.renderedQuestion, it.renderedAnswer, it.tags.each { it.replaceAll(" ", "_")}.join(" ")]
      def line = columns.collect { encode(it) }.join(";")
      file << (line + "\n")
    }
  }

  String encode(String column) {
    if (column.contains("\n") || column.contains(";")) {
      column = '"' + column.replaceAll('"', '""') + '"'
    }
    return column
  }
}

class AnkiProcessor {
  private static final int AFTER_ROOT_IDX = 1
  private static final int ROOT_AND_CURRENT_NODE_COUNT = 2

  boolean generateCsv
  boolean generateHtml

  private HtmlExporter htmlExporter = new HtmlExporter(dir: '/home/ivaneh/Downloads/Anki')
  private CsvExporter csvExporter = new CsvExporter(dir: '/home/ivaneh/Downloads')

  void process(Node node) {
    def context = createContext(node)

    visitNode(node, context)

    if (generateHtml) {
      htmlExporter.export(context.cards)
    }
    if (generateCsv) {
        csvExporter.export(context.cards)
    }
    println "Exported"
    println ""
  }

  static StatefulContext createContext(Node node) {
    def context = new StatefulContext()
    context.cards = []
    context.category = new StatefulCategory()
    def path = node.pathToRoot

    if (path.size() > ROOT_AND_CURRENT_NODE_COUNT) {
      path.subList(AFTER_ROOT_IDX, path.size() - 1).each {
        context.node = it
        Visitors.BEFORE_NODE[Utilities.getType(it)]?.visit(context)
      }
    }

    println "created context $context"
    return context
  }

  void visitNode(Node node, StatefulContext context) {
    println ">> Processing node ${node.plainText.substring(0, Math.min(50, node.plainText.length()))}"
    if (node['ignore'].text == 'true') {
      return
    }

    def nodeType = Utilities.getType(node)

    def text = node.displayedText
    println "  >> Visitting $nodeType : ${text.substring(0, Math.min(text.length(), 70))}"
    context.node = node

    def visitor = Visitors.BEFORE_NODE[nodeType]
    visitor?.visit(context)

    node.children.each {
      visitNode(it, context)
    }

    Visitors.AFTER_NODE[nodeType]?.visit(context)
  }
}

AnkiProcessor ankiProcessor = new AnkiProcessor(generateCsv: true, generateHtml: true)
ankiProcessor.process(node)










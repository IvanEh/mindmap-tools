// @ExecutionModes({ON_SELECTED_NODE_RECURSIVELY})

import groovy.transform.ToString
import org.freeplane.api.ExternalObject
import org.freeplane.api.MindMapRO
import org.freeplane.api.Node

import java.awt.Color

interface NodeTypeRule {
  boolean apply(Node node)
  boolean isApplicable(Node node)
}

class TopLevelNodeRule implements NodeTypeRule {
  private final Map<String, String> levelStyles = [
          '1': 'Subject',
          '2': 'Topic',
          '3': 'Subtopic'
  ]

  @Override
  boolean apply(Node node) {
      def level = node.getNodeLevel(true)
      def suggestedStyle = levelStyles[level.toString()]
      if (suggestedStyle) {
          node.style.name = suggestedStyle
          return true
      }
      return false
  }

  @Override
  boolean isApplicable(Node node) {
    return node.getNodeLevel(true) <= 3
  }
}

class ShorthandQuestionRule implements NodeTypeRule {
  private QuestionRule questionRule = new QuestionRule()

  @Override
  boolean apply(Node node) {
    if (!node.icons.contains('help')) {
      node.icons.add('help')
    }

    def newText = node.plainText.replace("@", "").trim()
    node.text = newText
    node.details = "What is the ^ of *"

    questionRule.apply(node)

    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return node.plainText.contains('@')
  }
}

class SimpleQuestionRule implements NodeTypeRule {
  private QuestionRule questionRule = new QuestionRule()

  @Override
  boolean apply(Node node) {
    if (!node.icons.contains('help')) {
      node.icons.add('help')
    }
    def newText = node.plainText.substring(0, node.plainText.length() - 1).trim()
    node.text = newText

    questionRule.apply(node)

    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return node.plainText.endsWith('?')
  }
}

class QuestionRule implements NodeTypeRule {

  @Override
  boolean apply(Node node) {
    node.style.name = 'Question'
    applyContentChanges(node)
  }

  @Override
  boolean isApplicable(Node node) {
    return node.icons.contains('help')
  }

  private void applyContentChanges(Node node) {
    applyAliases(node)
    grayOutQuestionDetails(node)
  }

  private void applyAliases(Node node) {
    def detailsAliases = [
            'is'              : 'What is *',
            'alternative name': 'What is the alternative<br/> name of *'
    ]
    def detailAlias = detailsAliases[node.plainText]
    if (detailAlias && node.details?.plain == null) {
      node.details = detailAlias
    }
  }

  private void grayOutQuestionDetails(Node node) {
    def currDetailsHtml = node.detailsText
    if (currDetailsHtml != null && !currDetailsHtml.contains('<font color="#c3c3c3')) {
      def bodyStarts = currDetailsHtml.indexOf('<body>') + '<body>'.length()
      def bodyEnds = currDetailsHtml.indexOf('</body>')
      def formattedDetails = currDetailsHtml.substring(0, bodyStarts) +
              '<font color="#c3c3c3" size="1">' + currDetailsHtml.substring(bodyStarts, bodyEnds) +
              '</font>' + currDetailsHtml.substring(bodyEnds)
      node.detailsText = formattedDetails
    }
  }
}

class AnswerRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    node.style.name = 'Answer'
    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return node.parent?.icons?.contains('help') && !node.icons.contains('idea')
  }
}

class HintRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    if (!node.icons.contains('idea'))
      node.icons.add('idea')
    node.style.name = 'Hint'
    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return (node.parent?.icons?.contains('help') && node.children.size() > 0) || node.icons.contains('idea')
  }
}

class FallbackConceptRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    node.style.name = 'Concept'
    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return node.parent != null && (node.style.name == null || node.style.name == 'Default')
  }
}

class BackwardQuestionRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    def firstChild = node.children.first()
    if (!firstChild.connectorsOut.find { it.target.nodeID == node.nodeID }) {
      def connector = firstChild.addConnectorTo(node)
      connector.shape = 'CUBIC_CURVE'
      firstChild.verticalShift = "1 cm"
      connector.setInclination([130, 0], [123, -14])
    }

    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return (node.icons.contains('back') || node.icons.contains('forward')) && node.children.size() > 0
  }
}

class BackwardQuestionFromAnswerRule implements NodeTypeRule {
  private final BackwardQuestionRule backwardQuestionRule = new BackwardQuestionRule()

  @Override
  boolean apply(Node node) {
    def inverse = node.icons.contains('back') ? 'forward' : 'back'
    node.icons.remove('back')
    node.icons.remove('forward')
    backwardQuestionRule.apply(node.parent)
  }

  @Override
  boolean isApplicable(Node node) {
    return (node.icons.contains('back') || node.icons.contains('forward')) && node.children.size() == 0 && node.style.name == 'Answer'
  }
}

class GrayConnectorsRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    def connectors = node.connectorsIn + node.connectorsOut
    connectors.each {
      it.color = new Color(0, 0, 0, 30)
    }
    return true
  }

  @Override
  boolean isApplicable(Node node) {
    return node.connectorsIn.size() > 0 || node.connectorsOut.size() > 0
  }
}

class FixAnswerHintRule implements NodeTypeRule {
  @Override
  boolean apply(Node node) {
    node.icons.remove('idea')
    node.style.name = null
  }

  @Override
  boolean isApplicable(Node node) {
    return node.icons.contains('idea') && node.children.size() == 0
  }
}

class StyleProcessor {
  private final Node node
  private final boolean nonDefaultInitialStyle

  private final FixAnswerHintRule fixAnswerHintRule = new FixAnswerHintRule()

  private final TopLevelNodeRule topLevelStyleRule = new TopLevelNodeRule()
  private final ShorthandQuestionRule shorthandQuestionRule = new ShorthandQuestionRule()
  private final SimpleQuestionRule simpleQuestionRule = new SimpleQuestionRule()
  private final QuestionRule questionRule = new QuestionRule()
  private final AnswerRule answerRule = new AnswerRule()
  private final HintRule hintRule = new HintRule()
  private final FallbackConceptRule fallbackConceptRule = new FallbackConceptRule()

  private final BackwardQuestionRule backwardQuestionRule = new BackwardQuestionRule()
  private final BackwardQuestionFromAnswerRule backwardQuestionFromAnswerRule = new BackwardQuestionFromAnswerRule()
  private final GrayConnectorsRule grayConnectorsRule = new GrayConnectorsRule()

  StyleProcessor(Node node) {
    this.node = node
  }

  void apply() {
    println ">>> Processing ${node.plainText.substring(0, Math.min(node.plainText.length(), 40))}"
    println "Input information: style=${node.style.name}, icons=${node.icons.icons}, level=${node.getNodeLevel(true)}, children=${node.children.size()}}"

    applyInclusiveNodeTypes(fixAnswerHintRule)
    applyExclusiveNodeType(topLevelStyleRule, shorthandQuestionRule, simpleQuestionRule, questionRule, hintRule, answerRule, fallbackConceptRule)
    applyInclusiveNodeTypes(backwardQuestionRule, grayConnectorsRule, backwardQuestionFromAnswerRule)

    println "Icons ${node.icons.icons}"
    println ""
  }

  private void applyQuestionConnectors() {
  }

  private void applyExclusiveNodeType(NodeTypeRule... nodeTypeRules) {
    for (NodeTypeRule rule: nodeTypeRules) {
        if (rule.isApplicable(node)) {
          println "Trying to apply rule ${rule.class.simpleName}"
          if (rule.apply(node)) {
            println "Applied rule ${rule.class.simpleName}"
            return
          }
        }
    }
  }

  private void applyInclusiveNodeTypes(NodeTypeRule... nodeTypeRules) {
    for (NodeTypeRule rule: nodeTypeRules) {
      if (rule.isApplicable(node)) {
        println "Applying rule ${rule.class.simpleName}"
        if (rule.apply(node)) {
          println "Successfully applied rule ${rule.class.simpleName}"
        }
      }
    }
  }
}

StyleProcessor styleProcessor = new StyleProcessor(this.node)
styleProcessor.apply()

package lesson7

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....
import jdk.jfr.Event
import lesson5.Listener
import lesson6.GameState

import java.io.File
import java.security.Guard

// В игре которая зависит от общего игрового процесса игроков - клиент не должен уметь менять квесты, золото, инвентарь
// Клиент иначе можно будет взломаьт, только сервер будет решать что можно а что нельзя и сервер синхронизирует всё между игроками

// Аннотации - разделение кусков кода на серверные и клиентские (мы сами говорим программе что где должно работать)
// Правильная цепочка безопасного кода
// 1. Клиент (через hub или кнопку) отпавляет команду на сервер:
// "Я поговорил с алхимиком"
// 2. Сервер принимает команду проверяет правила которые ему установили (соблюдено ли условие 5 золота)
// 3. Сервер рассылает событие (GameEvent) с информацией (Reward / Refuse)
// 4. Клиент получает информацию о том можно ли пройти дальше

enum class QuestState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val id: String,
    val name: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        return when(state){
            QuestState.START -> DialogueView(
                name,
                "Привет! нажми talk чтобы начать диалог",
                listOf(
                    DialogueOption("talk", "Говорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                name,
                "помоги или казнь",
                listOf(
                    DialogueOption("help", "помочь"),
                    DialogueOption("threat", "казнь так казнь")
                )
            )
            QuestState.HELP_ACCEPTED -> DialogueView(
                name,
                "ура спс",
                emptyList()
            )
            QuestState.THREAT_ACCEPTED -> DialogueView(
                name,
                "ну всё смэрть",
                emptyList()
            )
            QuestState.GOOD_END -> DialogueView(
                name,
                "хэппи энд",
                emptyList()
            )
            QuestState.EVIL_END -> DialogueView(
                name,
                "бэд энд",
                emptyList()
            )
        }

    }
}

// GameState (показывает только HUB)

class ClientUiState{
    // состояния внутри него будут обновляться от серверных данных

    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf(QuestState.START)
    val networkLagMs = mutableStateOf(350)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: ClientUiState, text: String) {
    ui.log.value = (ui.log.value + text).takeLast(20)
}

sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

typealias Listener = (GameState) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()
    // Список всех, кто реагирует на события (слушателей)
    // private - позволяет читать, вызывать и использовать список только внутри класса (сейчас только внутри GameEvent)
    fun subscribe(listener: Listener){
        listeners.add(listener)
        // .add - добавляет в конец списка
    }

    fun publish(event: GameEvent){
        // Метод рассылки событий для слушателей
        for (l in listeners){
            l(event)
        }
    }
}

// Команды - "запрос клиента на сервер"

sealed class GameCommand{
    val playerId: String
}

data class CmdTalkToNpc(
    override val playerId: String,
    val npcId: String,
): GameCommand()

data class CmdSelectChoice(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameCommand()

data class CmdLoadPlayer(
    override val playerId: String,
): GameCommand()

// SERVER WORLD - серверные данные и обработка команд

//PlayerData
data class PlayerData(
    var hp: Int,
    var gold: Int,
    var questState: QuestState
)

//комманда которая ждёт выполнения (симуляция пинга)
data class PendingCommand(
    val cmd: GameCommand,
    var delayLeftSec: Float
)
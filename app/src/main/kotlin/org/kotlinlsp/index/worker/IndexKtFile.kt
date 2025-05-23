package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.kotlinlsp.common.read
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.db.*
import java.time.Instant

fun indexKtFile(project: Project, ktFile: KtFile, db: Database) {
    val newFile = File.fromKtFile(ktFile, project, indexed = true)

    val existingFile = db.file(newFile.path)
    if (
        File.shouldBeSkipped(existingFile = existingFile, newFile = newFile) &&
        existingFile?.indexed == true  // Already indexed
    ) return

    // Update the file timestamp and package
    db.setFile(newFile)

    // TODO Remove declarations for this file first
    project.read {
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDeclaration(dcl: KtDeclaration) {
                val decl = analyze(dcl) {
                    analyzeDeclaration(newFile.path, dcl)
                } ?: return

                db.putDeclaration(decl) // TODO Put all declarations in bulk to improve performance

                super.visitDeclaration(dcl)
            }

            // TODO Store references
            /*override fun visitReferenceExpression(e: KtReferenceExpression) {
                super.visitReferenceExpression(e)
                if (e !is KtNameReferenceExpression) return

                val target = try {
                    e.mainReference.resolve()
                } catch (_: Exception) {
                    null
                }

                if (target == null) {
                    warn("Unresolved reference: ${e.text}")
                    return
                }

                val referenceRecord = ReferenceRecord(
                    id = -1,
                    symbolId = 1,
                    startOffset = e.textOffset,
                    endOffset = e.endOffset
                )

                debug("REFERENCE: $referenceRecord")
                debug("-> Name: ${e.text}")
                debug("-> Target: ${target?.containingFile?.virtualFile?.url}")
            }*/
        })
    }
}

private fun KaSession.analyzeDeclaration(path: String, dcl: KtDeclaration): Declaration? {
    val name = dcl.name ?: return null
    val startOffset = dcl.textOffset
    val endOffset = dcl.textOffset + name.length

    return when (dcl) {
        is KtNamedFunction -> {
            val parentFqName = if (dcl.parent is KtClassBody) {
                (dcl.parent.parent as? KtClassOrObject)?.fqName?.asString() ?: ""
            } else ""

            Declaration.Function(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.valueParameters.map {
                    Declaration.Function.Parameter(
                        it.nameAsSafeName.asString(),
                        it.returnType.toString()
                    )
                },
                dcl.returnType.toString(),
                parentFqName,
                dcl.receiverTypeReference?.type?.toString() ?: ""
            )
        }

        is KtClass -> {
            if (dcl is KtEnumEntry) {
                return Declaration.EnumEntry(
                    name,
                    dcl.fqName?.asString() ?: "",
                    path,
                    startOffset,
                    endOffset,
                    dcl.parentOfType<KtClass>()?.fqName?.asString() ?: ""
                )
            }

            val type = if (dcl.isEnum()) {
                Declaration.Class.Type.ENUM_CLASS
            } else if (dcl.isAnnotation()) {
                Declaration.Class.Type.ANNOTATION_CLASS
            } else if (dcl.isInterface()) {
                Declaration.Class.Type.INTERFACE
            } else if (dcl.isAbstract()) {
                Declaration.Class.Type.ABSTRACT_CLASS
            } else {
                Declaration.Class.Type.CLASS
            }

            Declaration.Class(
                name,
                type,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset
            )
        }

        is KtParameter -> {
            if (!dcl.hasValOrVar()) return null
            val constructor = dcl.parentOfType<KtPrimaryConstructor>() ?: return null
            val clazz = constructor.parentOfType<KtClass>() ?: return null

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                clazz.fqName?.asString() ?: ""
            )
        }

        is KtProperty -> {
            if (dcl.isLocal) return null
            val clazz = dcl.parentOfType<KtClass>() ?: return Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                ""
            )

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset,
                dcl.returnType.toString(),
                clazz.fqName?.asString() ?: ""
            )
        }

        else -> {
            // TODO Handle other declarations
            warn("Declaration type not handled: ${dcl::class.simpleName}")
            null
        }
    }
}
